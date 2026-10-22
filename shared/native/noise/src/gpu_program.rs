use std::cell::RefCell;
use std::collections::HashMap;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};

use crate::gpu_wgsl::{specialize, RecordSpec, Specialized, RECORD_WORDS, WORKGROUP};
use crate::program::{encoded_size, Header, Program};
use crate::tables::{CELL_2D_X, CELL_2D_Y, CELL_3D_X, CELL_3D_Y, CELL_3D_Z};

const MAX_POINTS: usize = 1 << 25;
const MAX_EXTENT: f64 = 1024.0;
const MAX_GROUPS_X: u32 = 32768;

pub(crate) struct Gpu {
    device: wgpu::Device,
    queue: wgpu::Queue,
    name: String,
    lost: Arc<AtomicBool>,
    cells: wgpu::Buffer,
}

struct Buffers {
    capacity: usize,
    dims: wgpu::Buffer,
    records: wgpu::Buffer,
    output: wgpu::Buffer,
    readback: wgpu::Buffer,
    group: wgpu::BindGroup,
    words: Vec<u32>,
}

pub(crate) struct GpuProgram {
    records: Vec<RecordSpec>,
    volumetric: bool,
    uses_cells: bool,
    pipeline: wgpu::ComputePipeline,
    state: Mutex<Option<Buffers>>,
}

#[derive(Default, Clone, Copy)]
struct Timing {
    prepare: i64,
    execute: i64,
    copy: i64,
}

static GPU: OnceLock<Option<Gpu>> = OnceLock::new();
static PROGRAMS: OnceLock<Mutex<HashMap<i64, Arc<GpuProgram>>>> = OnceLock::new();
static NEXT: AtomicI64 = AtomicI64::new(1);

thread_local! {
    static OUTPUT: RefCell<Vec<f32>> = const { RefCell::new(Vec::new()) };
    static TIMING: RefCell<Timing> = RefCell::new(Timing::default());
}

fn programs() -> &'static Mutex<HashMap<i64, Arc<GpuProgram>>> {
    PROGRAMS.get_or_init(|| Mutex::new(HashMap::new()))
}

pub(crate) fn gpu() -> Option<&'static Gpu> {
    GPU.get_or_init(open).as_ref()
}

fn open() -> Option<Gpu> {
    for adapter in crate::gpu::candidate_adapters() {
        if !adapter.get_downlevel_capabilities().flags.contains(wgpu::DownlevelFlags::COMPUTE_SHADERS) {
            continue;
        }
        let available: wgpu::Limits = adapter.limits();
        let limits: wgpu::Limits = wgpu::Limits {
            max_storage_buffer_binding_size: available.max_storage_buffer_binding_size.min((MAX_POINTS * 4) as u64),
            max_buffer_size: available.max_buffer_size.min((MAX_POINTS * 4) as u64),
            ..Default::default()
        };
        let Ok((device, queue)) = pollster::block_on(adapter.request_device(&wgpu::DeviceDescriptor {
            label: Some("VolmLib Noise Programs"),
            required_limits: limits,
            ..Default::default()
        })) else {
            continue;
        };
        let lost: Arc<AtomicBool> = Arc::new(AtomicBool::new(false));
        let handler: Arc<AtomicBool> = lost.clone();
        device.set_device_lost_callback(move |_, _| handler.store(true, Ordering::Relaxed));
        let mut table: Vec<f32> = vec![0.0; 512 * 4];
        for index in 0..256 {
            table[index * 4] = CELL_2D_X[index] as f32;
            table[index * 4 + 1] = CELL_2D_Y[index] as f32;
            table[(256 + index) * 4] = CELL_3D_X[index] as f32;
            table[(256 + index) * 4 + 1] = CELL_3D_Y[index] as f32;
            table[(256 + index) * 4 + 2] = CELL_3D_Z[index] as f32;
        }
        let cells: wgpu::Buffer = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("cells"),
            size: (table.len() * 4) as u64,
            usage: wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        queue.write_buffer(&cells, 0, bytemuck::cast_slice(&table));
        let info: wgpu::AdapterInfo = adapter.get_info();
        return Some(Gpu { device, queue, name: format!("{} ({:?})", info.name, info.backend), lost, cells });
    }
    None
}

impl Gpu {
    fn usable(&self) -> bool {
        !self.lost.load(Ordering::Relaxed)
    }

    fn compile(&self, specialized: Specialized) -> Option<GpuProgram> {
        let scope = self.device.push_error_scope(wgpu::ErrorFilter::Validation);
        let module: wgpu::ShaderModule = self.device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("noise program"),
            source: wgpu::ShaderSource::Wgsl(specialized.source.as_str().into()),
        });
        let pipeline: wgpu::ComputePipeline = self.device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
            label: Some("noise program"),
            layout: None,
            module: &module,
            entry_point: Some("main"),
            compilation_options: Default::default(),
            cache: None,
        });
        if pollster::block_on(scope.pop()).is_some() {
            return None;
        }
        Some(GpuProgram {
            records: specialized.records,
            volumetric: specialized.volumetric,
            uses_cells: specialized.uses_cells,
            pipeline,
            state: Mutex::new(None),
        })
    }
}

impl GpuProgram {
    fn buffers(&self, gpu: &Gpu, points: usize) -> Buffers {
        let capacity: usize = points.next_power_of_two().max(4096);
        let create = |label: &str, size: u64, usage: wgpu::BufferUsages| {
            gpu.device.create_buffer(&wgpu::BufferDescriptor { label: Some(label), size, usage, mapped_at_creation: false })
        };
        let record_words: usize = (self.records.len() * RECORD_WORDS).max(RECORD_WORDS);
        let dims: wgpu::Buffer = create("dims", 48, wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST);
        let records: wgpu::Buffer =
            create("records", (record_words * 4) as u64, wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_DST);
        let output: wgpu::Buffer =
            create("output", (capacity * 4) as u64, wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_SRC);
        let readback: wgpu::Buffer =
            create("readback", (capacity * 4) as u64, wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::COPY_DST);
        let mut entries: Vec<wgpu::BindGroupEntry> = vec![
            wgpu::BindGroupEntry { binding: 0, resource: dims.as_entire_binding() },
            wgpu::BindGroupEntry { binding: 1, resource: records.as_entire_binding() },
            wgpu::BindGroupEntry { binding: 2, resource: output.as_entire_binding() },
        ];
        if self.uses_cells {
            entries.push(wgpu::BindGroupEntry { binding: 3, resource: gpu.cells.as_entire_binding() });
        }
        let group: wgpu::BindGroup = gpu.device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: None,
            layout: &self.pipeline.get_bind_group_layout(0),
            entries: &entries,
        });
        Buffers { capacity, dims, records, output, readback, group, words: vec![0; record_words] }
    }

    fn run(&self, gpu: &Gpu, origin: [f64; 3], step: [f32; 3], counts: [u32; 3], output: &mut Vec<f32>) -> Result<Timing, i32> {
        let started: Instant = Instant::now();
        let total: usize = counts[0] as usize * counts[1] as usize * counts[2] as usize;
        if total == 0 || total > MAX_POINTS {
            return Err(0);
        }
        let mut guard = self.state.lock().map_err(|_| -1)?;
        if guard.as_ref().is_none_or(|buffers| buffers.capacity < total) {
            *guard = Some(self.buffers(gpu, total));
        }
        let buffers: &mut Buffers = guard.as_mut().ok_or(-1)?;
        let half: [f64; 3] = [(counts[0] - 1) as f64 * 0.5, (counts[1] - 1) as f64 * 0.5, (counts[2] - 1) as f64 * 0.5];
        let center: [f64; 3] = if self.volumetric {
            [origin[0] + half[0] * step[0] as f64, origin[1] + half[1] * step[1] as f64, origin[2] + half[2] * step[2] as f64]
        } else {
            [origin[0] + half[0] * step[0] as f64, origin[1] + half[2] * step[2] as f64, 0.0]
        };
        for (index, record) in self.records.iter().enumerate() {
            if !record.write(&center, &mut buffers.words[index * RECORD_WORDS..(index + 1) * RECORD_WORDS]) {
                return Err(0);
            }
        }
        let groups: u32 = (total as u32).div_ceil(WORKGROUP);
        let groups_x: u32 = groups.min(MAX_GROUPS_X);
        let groups_y: u32 = groups.div_ceil(groups_x);
        let dims: [u32; 12] = [
            counts[0],
            counts[1],
            counts[2],
            total as u32,
            step[0].to_bits(),
            step[1].to_bits(),
            step[2].to_bits(),
            groups_x * WORKGROUP,
            (half[0] as f32).to_bits(),
            (half[1] as f32).to_bits(),
            (half[2] as f32).to_bits(),
            0,
        ];
        gpu.queue.write_buffer(&buffers.dims, 0, bytemuck::cast_slice(&dims));
        gpu.queue.write_buffer(&buffers.records, 0, bytemuck::cast_slice(&buffers.words));
        let size: u64 = (total * 4) as u64;
        let mut encoder: wgpu::CommandEncoder = gpu.device.create_command_encoder(&Default::default());
        {
            let mut pass: wgpu::ComputePass = encoder.begin_compute_pass(&Default::default());
            pass.set_pipeline(&self.pipeline);
            pass.set_bind_group(0, &buffers.group, &[]);
            pass.dispatch_workgroups(groups_x, groups_y, 1);
        }
        encoder.copy_buffer_to_buffer(&buffers.output, 0, &buffers.readback, 0, size);
        let prepared: Instant = Instant::now();
        gpu.queue.submit([encoder.finish()]);
        let (sender, receiver) = std::sync::mpsc::sync_channel(1);
        buffers.readback.slice(..size).map_async(wgpu::MapMode::Read, move |status| {
            let _ = sender.send(status);
        });
        if gpu
            .device
            .poll(wgpu::PollType::Wait { submission_index: None, timeout: Some(Duration::from_secs(30)) })
            .is_err()
            || !matches!(receiver.recv_timeout(Duration::from_secs(1)), Ok(Ok(())))
        {
            return Err(-1);
        }
        let executed: Instant = Instant::now();
        {
            let Ok(mapped) = buffers.readback.slice(..size).get_mapped_range() else {
                buffers.readback.unmap();
                return Err(-1);
            };
            let values: &[f32] = bytemuck::cast_slice(&mapped);
            output.clear();
            output.extend_from_slice(values);
        }
        buffers.readback.unmap();
        let copied: Instant = Instant::now();
        Ok(Timing {
            prepare: (prepared - started).as_nanos() as i64,
            execute: (executed - prepared).as_nanos() as i64,
            copy: (copied - executed).as_nanos() as i64,
        })
    }
}

fn dispatch(handle: i64, volumetric: bool, origin: [f64; 3], step: [f64; 3], counts: [i32; 3]) -> *const f32 {
    let result: Result<*const f32, _> = catch_unwind(AssertUnwindSafe(|| {
        if counts.iter().any(|count| *count <= 0) || step.iter().chain(origin.iter()).any(|value| !value.is_finite()) {
            return std::ptr::null();
        }
        if (0..3).any(|axis| (counts[axis] - 1) as f64 * step[axis].abs() > MAX_EXTENT) {
            return std::ptr::null();
        }
        let Some(gpu) = gpu() else {
            return std::ptr::null();
        };
        if !gpu.usable() {
            return std::ptr::null();
        }
        let program: Option<Arc<GpuProgram>> = programs().lock().ok().and_then(|map| map.get(&handle).cloned());
        let Some(program) = program else {
            return std::ptr::null();
        };
        if program.volumetric != volumetric {
            return std::ptr::null();
        }
        let steps: [f32; 3] = [step[0] as f32, step[1] as f32, step[2] as f32];
        let sizes: [u32; 3] = [counts[0] as u32, counts[1] as u32, counts[2] as u32];
        OUTPUT.with(|cell| match cell.try_borrow_mut() {
            Ok(mut output) => match program.run(gpu, origin, steps, sizes, &mut output) {
                Ok(timing) => {
                    TIMING.with(|slot| *slot.borrow_mut() = timing);
                    output.as_ptr()
                }
                Err(_) => std::ptr::null(),
            },
            Err(_) => std::ptr::null(),
        })
    }));
    result.unwrap_or(std::ptr::null())
}

#[unsafe(no_mangle)]
pub extern "C" fn volmlib_gpu_open() -> i32 {
    catch_unwind(|| gpu().is_some_and(Gpu::usable) as i32).unwrap_or(0)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn volmlib_gpu_adapter(buffer: *mut u8, capacity: i32) -> i32 {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(gpu) = gpu() else {
            return 0;
        };
        if buffer.is_null() || capacity <= 0 {
            return 0;
        }
        let bytes: &[u8] = gpu.name.as_bytes();
        let length: usize = bytes.len().min(capacity as usize);
        unsafe { std::ptr::copy_nonoverlapping(bytes.as_ptr(), buffer, length) };
        length as i32
    }))
    .unwrap_or(0)
}

#[unsafe(no_mangle)]
pub extern "C" fn volmlib_gpu_compile(program: *const u8, length: i64) -> i64 {
    catch_unwind(|| {
        if program.is_null() || length < size_of::<Header>() as i64 || !(program as usize).is_multiple_of(8) {
            return 0;
        }
        let header: &Header = unsafe { &*(program as *const Header) };
        if encoded_size(header).is_none_or(|size| size as i64 != length) {
            return 0;
        }
        let Some(view) = (unsafe { Program::view(program) }) else {
            return 0;
        };
        if !view.validate() {
            return 0;
        }
        let Some(specialized) = specialize(&view) else {
            return 0;
        };
        let Some(gpu) = gpu() else {
            return 0;
        };
        let Some(compiled) = gpu.compile(specialized) else {
            return 0;
        };
        let handle: i64 = NEXT.fetch_add(1, Ordering::Relaxed);
        match programs().lock() {
            Ok(mut map) => {
                map.insert(handle, Arc::new(compiled));
                handle
            }
            Err(_) => 0,
        }
    })
    .unwrap_or(0)
}

#[unsafe(no_mangle)]
pub extern "C" fn volmlib_gpu_release(handle: i64) {
    let _ = catch_unwind(|| {
        if let Ok(mut map) = programs().lock() {
            map.remove(&handle);
        }
    });
}

#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub extern "C" fn volmlib_gpu_lattice3d(
    handle: i64,
    x0: f64,
    y0: f64,
    z0: f64,
    step_x: f64,
    step_y: f64,
    step_z: f64,
    nx: i32,
    ny: i32,
    nz: i32,
) -> *const f32 {
    dispatch(handle, true, [x0, y0, z0], [step_x, step_y, step_z], [nx, ny, nz])
}

#[unsafe(no_mangle)]
pub extern "C" fn volmlib_gpu_grid2d(handle: i64, x0: f64, z0: f64, step: f64, width: i32, depth: i32) -> *const f32 {
    dispatch(handle, false, [x0, z0, 0.0], [step, 1.0, step], [width, depth, 1])
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn volmlib_gpu_timing(out: *mut i64) {
    if out.is_null() {
        return;
    }
    let timing: Timing = TIMING.with(|slot| *slot.borrow());
    unsafe {
        *out = timing.prepare;
        *out.add(1) = timing.execute;
        *out.add(2) = timing.copy;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::program::{Node, Scratch, Term, KIND_PERLIN, KIND_SIMPLEX, KIND_VASCULAR, MAGIC, MODE_SIGNED_2D, MODE_SIGNED_3D, VERSION};

    fn node(kind: i32, seed: i64, octaves: i32) -> Node {
        Node {
            kind,
            octaves,
            longerp: 2,
            fractal_type: 0,
            cell_distance: 2,
            cell_return: 5,
            injector: -1,
            unity_opacity: 1,
            identity_signed: 1,
            reserved: 0,
            seed,
            frequency: 1.0,
            ox: 1234.567_891,
            oz: -987.654_321,
            octave_bounding: if octaves == 1 { 1.0 } else { 1.0 / (2.0 - 2f64.powi(1 - octaves)) },
            lacunarity: 2.0,
            gain: 0.5,
            fractal_bounding: 1.0,
            scale: 0.031,
            fracture_scale: 40.0,
            signed_fracture_scale: 20.0,
            opacity: 1.0,
            up: 0.0,
            down: 0.0,
            patch: 1.0,
        }
    }

    fn term(mode: i32, node: i32, deps: [i32; 3], pair: i32) -> Term {
        Term {
            mode,
            node,
            axis0: 0,
            axis1: 1,
            axis2: 2,
            dep0: deps[0],
            dep1: deps[1],
            dep2: deps[2],
            pair,
            apply_post: 0,
            child_start: 0,
            child_count: 0,
        }
    }

    fn encode(nodes: &[Node], terms: &[Term], pair_terms: &[i32], volume_terms: &[i32], pair_used: [i32; 3]) -> Vec<u64> {
        let header: Header = Header {
            magic: MAGIC,
            version: VERSION,
            volumetric: 1,
            node_count: nodes.len() as i32,
            term_count: terms.len() as i32,
            child_count: 0,
            pair_term_count: pair_terms.len() as i32,
            volume_term_count: volume_terms.len() as i32,
            pair_used,
            reserved: 0,
        };
        let size: usize = encoded_size(&header).unwrap();
        let mut buffer: Vec<u64> = vec![0; size.div_ceil(8)];
        let base: *mut u8 = buffer.as_mut_ptr() as *mut u8;
        unsafe {
            std::ptr::write(base as *mut Header, header);
            let mut offset: usize = size_of::<Header>();
            for value in nodes {
                std::ptr::copy_nonoverlapping(value as *const Node as *const u8, base.add(offset), size_of::<Node>());
                offset += size_of::<Node>();
            }
            for value in terms {
                std::ptr::copy_nonoverlapping(value as *const Term as *const u8, base.add(offset), size_of::<Term>());
                offset += size_of::<Term>();
            }
            for value in pair_terms.iter().chain(volume_terms) {
                std::ptr::write(base.add(offset) as *mut i32, *value);
                offset += 4;
            }
        }
        buffer
    }

    fn worst(encoded: &[u64], origin: [f64; 3], step: [f64; 3], counts: [i32; 3]) -> Option<f64> {
        let pointer: *const u8 = encoded.as_ptr() as *const u8;
        let length: i64 = encoded_size(unsafe { &*(pointer as *const Header) }).unwrap() as i64;
        let handle: i64 = volmlib_gpu_compile(pointer, length);
        if handle == 0 {
            return None;
        }
        let result: *const f32 =
            volmlib_gpu_lattice3d(handle, origin[0], origin[1], origin[2], step[0], step[1], step[2], counts[0], counts[1], counts[2]);
        assert!(!result.is_null());
        let total: usize = (counts[0] * counts[1] * counts[2]) as usize;
        let values: Vec<f32> = unsafe { std::slice::from_raw_parts(result, total) }.to_vec();
        let view: Program = unsafe { Program::view(pointer) }.unwrap();
        let mut scratch: Scratch = Scratch::default();
        scratch.fill_lattice3d(&view, origin, step, counts[0] as usize, counts[1] as usize, counts[2] as usize);
        let mut delta: f64 = 0.0;
        let mut uncertified: usize = 0;
        for index in 0..total {
            if values[index].abs() >= 1.0e38 {
                uncertified += 1;
                continue;
            }
            delta = delta.max((values[index] as f64 - scratch.out[index]).abs());
        }
        volmlib_gpu_release(handle);
        assert!(uncertified * 20 < total, "uncertified {uncertified} of {total}");
        Some(delta)
    }

    #[test]
    fn leaf_kernels_track_the_exact_interpreter() {
        if volmlib_gpu_open() == 0 {
            return;
        }
        for kind in [KIND_SIMPLEX, KIND_PERLIN, KIND_VASCULAR] {
            for octaves in [1, 3] {
                if kind == KIND_VASCULAR && octaves > 1 {
                    continue;
                }
                let nodes: [Node; 1] = [node(kind, -77 + kind as i64, octaves)];
                let terms: [Term; 1] = [term(MODE_SIGNED_3D, 0, [-1, -1, -1], 0)];
                let encoded: Vec<u64> = encode(&nodes, &terms, &[], &[0], [0, 0, 0]);
                for origin in [[0.0, -64.0, 0.0], [29_999_872.0, 120.0, -29_999_616.0], [-1_048_576.5, 7.25, 4096.75]] {
                    let delta: f64 = worst(&encoded, origin, [8.0, 1.0, 8.0], [9, 40, 9]).unwrap();
                    let bound: f64 = 1.0e-5;
                    assert!(delta < bound, "kind {kind} octaves {octaves} origin {origin:?} delta {delta}");
                }
            }
        }
    }

    #[test]
    fn fracture_chain_tracks_the_exact_interpreter() {
        if volmlib_gpu_open() == 0 {
            return;
        }
        let nodes: [Node; 2] = [node(KIND_PERLIN, 4242, 1), node(KIND_SIMPLEX, 99, 1)];
        let fractured: Term = term(MODE_SIGNED_3D, 0, [0, 1, 2], 0);
        let leaf_a: Term = term(MODE_SIGNED_3D, 1, [-1, -1, -1], 0);
        let mut leaf_b: Term = term(MODE_SIGNED_2D, 1, [-1, -1, -1], 0);
        leaf_b.axis0 = 1;
        leaf_b.axis1 = 0;
        let mut leaf_c: Term = term(MODE_SIGNED_3D, 1, [-1, -1, -1], 0);
        leaf_c.axis0 = 2;
        leaf_c.axis1 = 0;
        leaf_c.axis2 = 1;
        let encoded: Vec<u64> = encode(&nodes, &[leaf_a, leaf_b, leaf_c, fractured], &[1], &[0, 2, 3], [1, 0, 0]);
        let delta: f64 = worst(&encoded, [-12_345_678.0, 0.0, 22_222_222.0], [4.0, 2.0, 4.0], [17, 33, 17]).unwrap();
        assert!(delta < 1.0e-4, "delta {delta}");
    }
}
