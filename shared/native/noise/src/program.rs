use crate::batch;
use crate::kernels::{
    java_max, java_min, white2, white3, Cellular, DISTANCE_EUCLIDEAN, DISTANCE_NATURAL, FRACTAL_BILLOW, FRACTAL_FBM, FRACTAL_RIGID_MULTI,
    LONGERP_LINEAR, LONGERP_QULONGIC, RETURN_CELL_VALUE, RETURN_DISTANCE2_DIV, RETURN_NOISE_LOOKUP,
};

pub const MAGIC: u32 = 0x564e_5052;
pub const VERSION: u32 = 1;
pub const BLOCK: usize = 256;

pub const MODE_UNSIGNED_2D: i32 = 0;
pub const MODE_SIGNED_2D: i32 = 2;
pub const MODE_UNSIGNED_3D: i32 = 3;
pub const MODE_SIGNED_3D: i32 = 5;

pub const KIND_SIMPLEX: i32 = 0;
pub const KIND_PERLIN: i32 = 1;
pub const KIND_BILLOW_SIMPLEX: i32 = 2;
pub const KIND_FBM_SIMPLEX: i32 = 3;
pub const KIND_RIGID_MULTI_SIMPLEX: i32 = 4;
pub const KIND_CELLULAR: i32 = 5;
pub const KIND_VASCULAR: i32 = 6;
pub const KIND_GLOB: i32 = 7;
pub const KIND_WHITE: i32 = 8;
const KIND_COUNT: i32 = KIND_WHITE + 1;

const INJECT_ADD: i32 = 0;
const INJECT_SRC_SUBTRACT: i32 = 1;
const INJECT_DST_SUBTRACT: i32 = 2;
const INJECT_MULTIPLY: i32 = 3;
const INJECT_MAX: i32 = 4;
const INJECT_MIN: i32 = 5;
const INJECT_SRC_MOD: i32 = 6;
const INJECT_DST_MOD: i32 = 8;

#[repr(C)]
pub struct Header {
    pub magic: u32,
    pub version: u32,
    pub volumetric: i32,
    pub node_count: i32,
    pub term_count: i32,
    pub child_count: i32,
    pub pair_term_count: i32,
    pub volume_term_count: i32,
    pub pair_used: [i32; 3],
    pub reserved: i32,
}

#[repr(C)]
pub struct Node {
    pub kind: i32,
    pub octaves: i32,
    pub longerp: i32,
    pub fractal_type: i32,
    pub cell_distance: i32,
    pub cell_return: i32,
    pub injector: i32,
    pub unity_opacity: i32,
    pub identity_signed: i32,
    pub reserved: i32,
    pub seed: i64,
    pub frequency: f64,
    pub ox: f64,
    pub oz: f64,
    pub octave_bounding: f64,
    pub lacunarity: f64,
    pub gain: f64,
    pub fractal_bounding: f64,
    pub scale: f64,
    pub fracture_scale: f64,
    pub signed_fracture_scale: f64,
    pub opacity: f64,
    pub up: f64,
    pub down: f64,
    pub patch: f64,
}

#[repr(C)]
pub struct Term {
    pub mode: i32,
    pub node: i32,
    pub axis0: i32,
    pub axis1: i32,
    pub axis2: i32,
    pub dep0: i32,
    pub dep1: i32,
    pub dep2: i32,
    pub pair: i32,
    pub apply_post: i32,
    pub child_start: i32,
    pub child_count: i32,
}

pub struct Program<'a> {
    pub header: &'a Header,
    pub nodes: &'a [Node],
    pub terms: &'a [Term],
    pub children: &'a [i32],
    pub pair_terms: &'a [i32],
    pub volume_terms: &'a [i32],
}

pub fn encoded_size(header: &Header) -> Option<usize> {
    let nodes: usize = usize::try_from(header.node_count).ok()?;
    let terms: usize = usize::try_from(header.term_count).ok()?;
    let children: usize = usize::try_from(header.child_count).ok()?;
    let pairs: usize = usize::try_from(header.pair_term_count).ok()?;
    let volumes: usize = usize::try_from(header.volume_term_count).ok()?;
    let fixed: usize = size_of::<Header>() + nodes * size_of::<Node>() + terms * size_of::<Term>();
    Some(fixed + (children + pairs + volumes) * size_of::<i32>())
}

impl<'a> Program<'a> {
    pub unsafe fn view(base: *const u8) -> Option<Program<'a>> {
        if base.is_null() || (base as usize) % 8 != 0 {
            return None;
        }
        let header: &Header = unsafe { &*(base as *const Header) };
        if header.magic != MAGIC || header.version != VERSION {
            return None;
        }
        let nodes: usize = header.node_count as usize;
        let terms: usize = header.term_count as usize;
        let mut offset: usize = size_of::<Header>();
        let node_slice: &[Node] = unsafe { std::slice::from_raw_parts(base.add(offset) as *const Node, nodes) };
        offset += nodes * size_of::<Node>();
        let term_slice: &[Term] = unsafe { std::slice::from_raw_parts(base.add(offset) as *const Term, terms) };
        offset += terms * size_of::<Term>();
        let children: &[i32] =
            unsafe { std::slice::from_raw_parts(base.add(offset) as *const i32, header.child_count as usize) };
        offset += header.child_count as usize * size_of::<i32>();
        let pair_terms: &[i32] =
            unsafe { std::slice::from_raw_parts(base.add(offset) as *const i32, header.pair_term_count as usize) };
        offset += header.pair_term_count as usize * size_of::<i32>();
        let volume_terms: &[i32] =
            unsafe { std::slice::from_raw_parts(base.add(offset) as *const i32, header.volume_term_count as usize) };
        Some(Program { header, nodes: node_slice, terms: term_slice, children, pair_terms, volume_terms })
    }

    pub fn validate(&self) -> bool {
        let term_count: i32 = self.header.term_count;
        let node_count: i32 = self.header.node_count;
        if term_count < 1 || node_count < 1 {
            return false;
        }
        let volumetric: bool = self.header.volumetric != 0;
        for node in self.nodes {
            if node.kind < 0 || node.kind >= KIND_COUNT || node.octaves < 1 || node.octaves > 16 {
                return false;
            }
            if !(LONGERP_LINEAR..=LONGERP_QULONGIC).contains(&node.longerp)
                || !(FRACTAL_FBM..=FRACTAL_RIGID_MULTI).contains(&node.fractal_type)
                || !(DISTANCE_EUCLIDEAN..=DISTANCE_NATURAL).contains(&node.cell_distance)
                || !(RETURN_CELL_VALUE..=RETURN_DISTANCE2_DIV).contains(&node.cell_return)
                || node.cell_return == RETURN_NOISE_LOOKUP
            {
                return false;
            }
        }
        for (index, term) in self.terms.iter().enumerate() {
            let index: i32 = index as i32;
            if term.node < 0 || term.node >= node_count || term.mode < MODE_UNSIGNED_2D || term.mode > MODE_SIGNED_3D {
                return false;
            }
            if !volumetric && term.mode >= MODE_UNSIGNED_3D {
                return false;
            }
            for axis in [term.axis0, term.axis1, term.axis2] {
                if !(0..3).contains(&axis) {
                    return false;
                }
            }
            if !(0..3).contains(&term.pair) {
                return false;
            }
            for dependency in [term.dep0, term.dep1, term.dep2] {
                if dependency >= index || dependency < -1 {
                    return false;
                }
            }
            if term.child_count < 0 || term.child_start < 0 || (term.child_start + term.child_count) as usize > self.children.len() {
                return false;
            }
            for child in &self.children[term.child_start as usize..(term.child_start + term.child_count) as usize] {
                if *child < 0 || *child >= index {
                    return false;
                }
            }
            if term.child_count > 0 {
                let injector: i32 = self.nodes[term.node as usize].injector;
                if !matches!(injector, INJECT_ADD | INJECT_SRC_SUBTRACT | INJECT_DST_SUBTRACT | INJECT_MULTIPLY | INJECT_MAX | INJECT_MIN | INJECT_SRC_MOD | INJECT_DST_MOD) {
                    return false;
                }
            }
            let needs_two: bool = term.dep0 >= 0
                && !(matches!(term.mode, MODE_SIGNED_2D | MODE_SIGNED_3D) && self.nodes[term.node as usize].identity_signed == 0);
            if needs_two && term.dep1 < 0 {
                return false;
            }
            if needs_two && term.mode >= MODE_UNSIGNED_3D && term.dep2 < 0 {
                return false;
            }
        }
        for list in [self.pair_terms, self.volume_terms] {
            for term in list {
                if *term < 0 || *term >= term_count {
                    return false;
                }
            }
        }
        (self.pair_terms.len() + self.volume_terms.len()) == self.terms.len() || !volumetric
    }
}

#[derive(Default)]
pub struct Scratch {
    values: Vec<Vec<f64>>,
    coordinates: [Vec<f64>; 3],
    projections: [Vec<u32>; 3],
    axes: [Vec<f64>; 3],
    pair_low: [Vec<f64>; 3],
    pair_high: [Vec<f64>; 3],
    work: Work,
    identity: Vec<u32>,
    pub input: Vec<f64>,
    pub out: Vec<f64>,
}

#[derive(Default)]
struct Work {
    u: Vec<f64>,
    v: Vec<f64>,
    w: Vec<f64>,
    a: Vec<f64>,
    b: Vec<f64>,
    c: Vec<f64>,
    sum: Vec<f64>,
    n: Vec<f64>,
}

fn grow<T: Copy + Default>(buffer: &mut Vec<T>, length: usize) {
    if buffer.len() < length {
        buffer.resize(length.max(BLOCK), T::default());
    }
}

impl Work {
    fn reserve(&mut self, length: usize) {
        grow(&mut self.u, length);
        grow(&mut self.v, length);
        grow(&mut self.w, length);
        grow(&mut self.a, length);
        grow(&mut self.b, length);
        grow(&mut self.c, length);
        grow(&mut self.sum, length);
        grow(&mut self.n, length);
    }
}

impl Scratch {
    fn prepare_terms(&mut self, terms: usize) {
        if self.values.len() < terms {
            self.values.resize_with(terms, Vec::new);
        }
    }

    pub fn fill_grid2d(&mut self, program: &Program, x0: f64, z0: f64, step: f64, width: usize, depth: usize) {
        let total: usize = width * depth;
        grow(&mut self.out, total);
        self.prepare_terms(program.terms.len());
        self.work.reserve(BLOCK);
        grow(&mut self.coordinates[0], BLOCK);
        grow(&mut self.coordinates[1], BLOCK);
        let mut x: usize = 0;
        let mut z: usize = 0;
        let mut start: usize = 0;
        while start < total {
            let length: usize = BLOCK.min(total - start);
            {
                let [cx, cz, _] = &mut self.coordinates;
                for index in 0..length {
                    cx[index] = x0 + x as f64 * step;
                    cz[index] = z0 + z as f64 * step;
                    x += 1;
                    if x == width {
                        x = 0;
                        z += 1;
                    }
                }
            }
            self.evaluate_planar(program, length);
            let root: &Vec<f64> = &self.values[program.terms.len() - 1];
            self.out[start..start + length].copy_from_slice(&root[..length]);
            start += length;
        }
    }

    pub fn fill2d(&mut self, program: &Program, count: usize) {
        grow(&mut self.out, count);
        self.prepare_terms(program.terms.len());
        self.work.reserve(BLOCK);
        grow(&mut self.coordinates[0], BLOCK);
        grow(&mut self.coordinates[1], BLOCK);
        let mut start: usize = 0;
        while start < count {
            let length: usize = BLOCK.min(count - start);
            self.coordinates[0][..length].copy_from_slice(&self.input[start..start + length]);
            self.coordinates[1][..length].copy_from_slice(&self.input[count + start..count + start + length]);
            self.evaluate_planar(program, length);
            let root: &Vec<f64> = &self.values[program.terms.len() - 1];
            self.out[start..start + length].copy_from_slice(&root[..length]);
            start += length;
        }
    }

    pub fn fill_points3d(&mut self, program: &Program, count: usize) {
        grow(&mut self.out, count);
        self.prepare_terms(program.terms.len());
        self.work.reserve(BLOCK);
        for axis in 0..3 {
            grow(&mut self.coordinates[axis], BLOCK);
        }
        if self.identity.len() < BLOCK {
            self.identity = (0..BLOCK as u32).collect();
        }
        let root: usize = program.terms.len() - 1;
        let mut start: usize = 0;
        while start < count {
            let length: usize = BLOCK.min(count - start);
            for axis in 0..3 {
                let offset: usize = axis * count + start;
                self.coordinates[axis][..length].copy_from_slice(&self.input[offset..offset + length]);
            }
            for term in 0..program.terms.len() {
                let descriptor: &Term = &program.terms[term];
                let first: &[f64] = &self.coordinates[descriptor.axis0 as usize];
                let second: &[f64] = &self.coordinates[descriptor.axis1 as usize];
                if descriptor.mode >= MODE_UNSIGNED_3D {
                    let third: &[f64] = &self.coordinates[descriptor.axis2 as usize];
                    evaluate_volume_term(program, &mut self.values, &mut self.work, term, first, second, third, &self.identity, length);
                } else {
                    evaluate_pair_term(program, &mut self.values, &mut self.work, term, first, second, length);
                }
            }
            self.out[start..start + length].copy_from_slice(&self.values[root][..length]);
            start += length;
        }
    }

    pub fn fill_column3d(&mut self, program: &Program, x: f64, z: f64, y0: f64, step: f64, count: usize) {
        grow(&mut self.axes[0], 1);
        grow(&mut self.axes[2], 1);
        grow(&mut self.axes[1], count);
        self.axes[0][0] = x;
        self.axes[2][0] = z;
        for index in 0..count {
            self.axes[1][index] = y0 + index as f64 * step;
        }
        self.evaluate_volume(program, 1, count, 1);
    }

    #[allow(clippy::too_many_arguments)]
    pub fn fill_lattice3d(
        &mut self,
        program: &Program,
        origin: [f64; 3],
        step: [f64; 3],
        nx: usize,
        ny: usize,
        nz: usize,
    ) {
        let counts: [usize; 3] = [nx, ny, nz];
        for axis in 0..3 {
            grow(&mut self.axes[axis], counts[axis]);
            for index in 0..counts[axis] {
                self.axes[axis][index] = origin[axis] + index as f64 * step[axis];
            }
        }
        self.evaluate_volume(program, nx, ny, nz);
    }

    fn evaluate_planar(&mut self, program: &Program, length: usize) {
        for term in 0..program.terms.len() {
            let descriptor: &Term = &program.terms[term];
            let first: &[f64] = &self.coordinates[descriptor.axis0 as usize];
            let second: &[f64] = &self.coordinates[descriptor.axis1 as usize];
            evaluate_pair_term(program, &mut self.values, &mut self.work, term, first, second, length);
        }
    }

    fn evaluate_volume(&mut self, program: &Program, nx: usize, ny: usize, nz: usize) {
        let total: usize = nx * ny * nz;
        grow(&mut self.out, total);
        self.prepare_terms(program.terms.len());
        let mut largest: usize = BLOCK;
        for pair in 0..3 {
            if program.header.pair_used[pair] != 0 {
                largest = largest.max(self.build_pair(pair, nx, ny, nz));
            }
        }
        self.work.reserve(largest);
        for &term in program.pair_terms {
            let term: usize = term as usize;
            let descriptor: &Term = &program.terms[term];
            let pair: usize = descriptor.pair as usize;
            let low_first: bool = descriptor.axis0 as usize == low_axis(pair);
            let size: usize = axis_size(low_axis(pair), nx, ny, nz) * axis_size(high_axis(pair), nx, ny, nz);
            let low: &[f64] = &self.pair_low[pair];
            let high: &[f64] = &self.pair_high[pair];
            let (first, second): (&[f64], &[f64]) = if low_first { (low, high) } else { (high, low) };
            evaluate_pair_term(program, &mut self.values, &mut self.work, term, first, second, size);
        }
        for axis in 0..3 {
            grow(&mut self.coordinates[axis], BLOCK);
            grow(&mut self.projections[axis], BLOCK);
        }
        let projected: bool = !program.pair_terms.is_empty();
        let mut x: usize = 0;
        let mut y: usize = 0;
        let mut z: usize = 0;
        let mut start: usize = 0;
        let root: usize = program.terms.len() - 1;
        while start < total {
            let length: usize = BLOCK.min(total - start);
            {
                let [cx, cy, cz] = &mut self.coordinates;
                let [pxy, pxz, pyz] = &mut self.projections;
                let [ax, ay, az] = &self.axes;
                for index in 0..length {
                    cx[index] = ax[x];
                    cy[index] = ay[y];
                    cz[index] = az[z];
                    if projected {
                        pxy[index] = (y * nx + x) as u32;
                        pxz[index] = (z * nx + x) as u32;
                        pyz[index] = (z * ny + y) as u32;
                    }
                    x += 1;
                    if x == nx {
                        x = 0;
                        z += 1;
                        if z == nz {
                            z = 0;
                            y += 1;
                        }
                    }
                }
            }
            for &term in program.volume_terms {
                let term: usize = term as usize;
                let descriptor: &Term = &program.terms[term];
                let first: &[f64] = &self.coordinates[descriptor.axis0 as usize];
                let second: &[f64] = &self.coordinates[descriptor.axis1 as usize];
                let third: &[f64] = &self.coordinates[descriptor.axis2 as usize];
                let projection: &[u32] = &self.projections[descriptor.pair as usize];
                evaluate_volume_term(program, &mut self.values, &mut self.work, term, first, second, third, projection, length);
            }
            self.out[start..start + length].copy_from_slice(&self.values[root][..length]);
            start += length;
        }
    }

    fn build_pair(&mut self, pair: usize, nx: usize, ny: usize, nz: usize) -> usize {
        let low_axis: usize = low_axis(pair);
        let high_axis: usize = high_axis(pair);
        let low_count: usize = axis_size(low_axis, nx, ny, nz);
        let high_count: usize = axis_size(high_axis, nx, ny, nz);
        let size: usize = low_count * high_count;
        grow(&mut self.pair_low[pair], size);
        grow(&mut self.pair_high[pair], size);
        let low: &mut Vec<f64> = &mut self.pair_low[pair];
        let high: &mut Vec<f64> = &mut self.pair_high[pair];
        let mut index: usize = 0;
        for h in 0..high_count {
            let high_value: f64 = self.axes[high_axis][h];
            for l in 0..low_count {
                low[index] = self.axes[low_axis][l];
                high[index] = high_value;
                index += 1;
            }
        }
        size
    }
}

fn low_axis(pair: usize) -> usize {
    if pair == 2 { 1 } else { 0 }
}

fn high_axis(pair: usize) -> usize {
    if pair == 0 { 1 } else { 2 }
}

fn axis_size(axis: usize, nx: usize, ny: usize, nz: usize) -> usize {
    match axis {
        0 => nx,
        1 => ny,
        _ => nz,
    }
}

fn take_buffer(values: &mut [Vec<f64>], term: usize, length: usize) -> Vec<f64> {
    let mut buffer: Vec<f64> = std::mem::take(&mut values[term]);
    grow(&mut buffer, length);
    buffer
}

fn evaluate_pair_term(
    program: &Program,
    values: &mut [Vec<f64>],
    work: &mut Work,
    term: usize,
    first: &[f64],
    second: &[f64],
    length: usize,
) {
    let descriptor: &Term = &program.terms[term];
    let node: &Node = &program.nodes[descriptor.node as usize];
    let mut out: Vec<f64> = take_buffer(values, term, length);
    let signed: bool = descriptor.mode == MODE_SIGNED_2D;
    if signed && node.identity_signed == 0 {
        let unsigned: &[f64] = &values[descriptor.dep0 as usize][..length];
        for (target, value) in out[..length].iter_mut().zip(unsigned) {
            *target = (*value * 2.0) - 1.0;
        }
        values[term] = out;
        return;
    }
    let scale: f64 = node.scale;
    let u: &mut [f64] = &mut work.u[..length];
    let v: &mut [f64] = &mut work.v[..length];
    let first: &[f64] = &first[..length];
    let second: &[f64] = &second[..length];
    if descriptor.dep0 < 0 {
        for index in 0..length {
            u[index] = first[index] * scale;
            v[index] = second[index] * scale;
        }
    } else if signed {
        let fracture: f64 = node.signed_fracture_scale;
        let fu: &[f64] = &values[descriptor.dep0 as usize][..length];
        let fv: &[f64] = &values[descriptor.dep1 as usize][..length];
        for index in 0..length {
            u[index] = (first[index] + (fu[index] * fracture)) * scale;
            v[index] = (second[index] + (fv[index] * fracture)) * scale;
        }
    } else {
        let fracture: f64 = node.fracture_scale;
        let fu: &[f64] = &values[descriptor.dep0 as usize][..length];
        let fv: &[f64] = &values[descriptor.dep1 as usize][..length];
        for index in 0..length {
            u[index] = (first[index] + ((fu[index] - 0.5) * fracture)) * scale;
            v[index] = (second[index] + ((fv[index] - 0.5) * fracture)) * scale;
        }
    }
    kernel2(node, signed, work, &mut out[..length], length);
    if signed {
        signed_opacity(node, &mut out[..length]);
    } else {
        let opacity: f64 = node.opacity;
        for value in out[..length].iter_mut() {
            *value *= opacity;
        }
        if descriptor.apply_post != 0 {
            post(program, descriptor, node, values, &mut out[..length]);
        }
    }
    values[term] = out;
}

#[allow(clippy::too_many_arguments)]
fn evaluate_volume_term(
    program: &Program,
    values: &mut [Vec<f64>],
    work: &mut Work,
    term: usize,
    first: &[f64],
    second: &[f64],
    third: &[f64],
    projection: &[u32],
    length: usize,
) {
    let descriptor: &Term = &program.terms[term];
    let node: &Node = &program.nodes[descriptor.node as usize];
    let mut out: Vec<f64> = take_buffer(values, term, length);
    let signed: bool = descriptor.mode == MODE_SIGNED_3D;
    if signed && node.identity_signed == 0 {
        let unsigned: &[f64] = &values[descriptor.dep0 as usize][..length];
        for (target, value) in out[..length].iter_mut().zip(unsigned) {
            *target = (*value * 2.0) - 1.0;
        }
        values[term] = out;
        return;
    }
    let scale: f64 = node.scale;
    let u: &mut [f64] = &mut work.u[..length];
    let v: &mut [f64] = &mut work.v[..length];
    let w: &mut [f64] = &mut work.w[..length];
    let first: &[f64] = &first[..length];
    let second: &[f64] = &second[..length];
    let third: &[f64] = &third[..length];
    if descriptor.dep0 < 0 {
        for index in 0..length {
            u[index] = first[index] * scale;
            v[index] = second[index] * scale;
            w[index] = third[index] * scale;
        }
    } else {
        let projection: &[u32] = &projection[..length];
        let fu: &[f64] = &values[descriptor.dep0 as usize][..length];
        let fv: &[f64] = &values[descriptor.dep1 as usize];
        let fw: &[f64] = &values[descriptor.dep2 as usize][..length];
        if signed {
            let fracture: f64 = node.signed_fracture_scale;
            for index in 0..length {
                u[index] = (first[index] + (fu[index] * fracture)) * scale;
                v[index] = (second[index] + (fv[projection[index] as usize] * fracture)) * scale;
                w[index] = (third[index] + (fw[index] * fracture)) * scale;
            }
        } else {
            let fracture: f64 = node.fracture_scale;
            for index in 0..length {
                u[index] = (first[index] + ((fu[index] - 0.5) * fracture)) * scale;
                v[index] = (second[index] + ((fv[projection[index] as usize] - 0.5) * fracture)) * scale;
                w[index] = (third[index] + ((fw[index] - 0.5) * fracture)) * scale;
            }
        }
    }
    kernel3(node, signed, work, &mut out[..length], length);
    if signed {
        signed_opacity(node, &mut out[..length]);
    } else {
        let opacity: f64 = node.opacity;
        for value in out[..length].iter_mut() {
            *value *= opacity;
        }
        if descriptor.apply_post != 0 {
            post(program, descriptor, node, values, &mut out[..length]);
        }
    }
    values[term] = out;
}

fn signed_opacity(node: &Node, out: &mut [f64]) {
    if node.unity_opacity != 0 {
        return;
    }
    let opacity: f64 = node.opacity;
    for value in out.iter_mut() {
        *value = ((*value + 1.0) * opacity) - 1.0;
    }
}

fn post(program: &Program, descriptor: &Term, node: &Node, values: &[Vec<f64>], out: &mut [f64]) {
    let down: f64 = node.down;
    let up: f64 = node.up;
    let patch: f64 = node.patch;
    let start: usize = descriptor.child_start as usize;
    let children: &[i32] = &program.children[start..start + descriptor.child_count as usize];
    if children.is_empty() {
        for value in out.iter_mut() {
            *value = ((*value / 1.0) - down + up) * patch;
        }
        return;
    }
    let injector: i32 = node.injector;
    let weight: f64 = match injector {
        INJECT_ADD => 1.0,
        INJECT_SRC_SUBTRACT | INJECT_DST_SUBTRACT => -1.0,
        _ => 0.0,
    };
    for (index, value) in out.iter_mut().enumerate() {
        let mut n: f64 = *value;
        let mut m: f64 = 1.0;
        for &child in children {
            n = combine(injector, n, values[child as usize][index]);
            m += weight;
        }
        *value = ((n / m) - down + up) * patch;
    }
}

#[inline(always)]
fn combine(injector: i32, source: f64, value: f64) -> f64 {
    match injector {
        INJECT_ADD => source + value,
        INJECT_SRC_SUBTRACT => {
            if source - value < 0.0 { 0.0 } else { source - value }
        }
        INJECT_DST_SUBTRACT => {
            if value - source < 0.0 { 0.0 } else { source - value }
        }
        INJECT_MULTIPLY => source * value,
        INJECT_MAX => java_max(source, value),
        INJECT_MIN => java_min(source, value),
        INJECT_SRC_MOD => source % value,
        _ => value % source,
    }
}

#[inline(always)]
fn unsigned(value: f64) -> f64 {
    (value / 2.0) + 0.5
}

fn kernel2(node: &Node, signed: bool, work: &mut Work, out: &mut [f64], length: usize) {
    let ox: f64 = node.ox;
    let oz: f64 = node.oz;
    let frequency: f64 = node.frequency;
    let seed: i64 = node.seed;
    match node.kind {
        KIND_SIMPLEX | KIND_PERLIN => octave2(node, signed, work, out, length),
        KIND_BILLOW_SIMPLEX | KIND_FBM_SIMPLEX | KIND_RIGID_MULTI_SIMPLEX => fractal2(node, signed, work, out, length),
        KIND_CELLULAR | KIND_VASCULAR | KIND_GLOB => {
            let Work { u, v, a, b, n, .. } = work;
            let u: &[f64] = &u[..length];
            let v: &[f64] = &v[..length];
            let xs: &mut [f64] = &mut a[..length];
            let ys: &mut [f64] = &mut b[..length];
            let raw: &mut [f64] = &mut n[..length];
            for index in 0..length {
                xs[index] = (u[index] + ox) * frequency;
                ys[index] = (v[index] + oz) * frequency;
            }
            batch::cellular2(&Cellular { seed, distance: node.cell_distance, result: node.cell_return }, xs, ys, raw);
            for index in 0..length {
                out[index] = cellular_wrap(node.kind, signed, raw[index]);
            }
        }
        _ => {
            let u: &[f64] = &work.u[..length];
            let v: &[f64] = &work.v[..length];
            for index in 0..length {
                let value: f64 = unsigned(white2(seed, u[index] + ox, v[index] + oz));
                out[index] = if signed { (value * 2.0) - 1.0 } else { value };
            }
        }
    }
}

fn kernel3(node: &Node, signed: bool, work: &mut Work, out: &mut [f64], length: usize) {
    let ox: f64 = node.ox;
    let oz: f64 = node.oz;
    let frequency: f64 = node.frequency;
    let seed: i64 = node.seed;
    match node.kind {
        KIND_SIMPLEX | KIND_PERLIN => octave3(node, signed, work, out, length),
        KIND_BILLOW_SIMPLEX | KIND_FBM_SIMPLEX | KIND_RIGID_MULTI_SIMPLEX => fractal3(node, signed, work, out, length),
        KIND_CELLULAR | KIND_VASCULAR | KIND_GLOB => {
            let Work { u, v, w, a, b, c, n, .. } = work;
            let u: &[f64] = &u[..length];
            let v: &[f64] = &v[..length];
            let w: &[f64] = &w[..length];
            let xs: &mut [f64] = &mut a[..length];
            let ys: &mut [f64] = &mut b[..length];
            let zs: &mut [f64] = &mut c[..length];
            let raw: &mut [f64] = &mut n[..length];
            for index in 0..length {
                xs[index] = (u[index] + ox) * frequency;
                ys[index] = v[index] * frequency;
                zs[index] = (w[index] + oz) * frequency;
            }
            batch::cellular3(&Cellular { seed, distance: node.cell_distance, result: node.cell_return }, xs, ys, zs, raw);
            for index in 0..length {
                out[index] = cellular_wrap(node.kind, signed, raw[index]);
            }
        }
        _ => {
            let u: &[f64] = &work.u[..length];
            let v: &[f64] = &work.v[..length];
            let w: &[f64] = &work.w[..length];
            for index in 0..length {
                let value: f64 = unsigned(white3(seed, u[index] + ox, v[index], w[index] + oz));
                out[index] = if signed { (value * 2.0) - 1.0 } else { value };
            }
        }
    }
}

#[inline(always)]
fn cellular_wrap(kind: i32, signed: bool, raw: f64) -> f64 {
    match kind {
        KIND_VASCULAR => {
            if signed {
                let negated: f64 = -raw;
                if negated < -1.0 {
                    -1.0
                } else if negated > 1.0 {
                    1.0
                } else {
                    negated
                }
            } else {
                let normalized: f64 = 0.5 - raw * 0.5;
                if normalized < 0.0 {
                    0.0
                } else if normalized > 1.0 {
                    1.0
                } else {
                    normalized
                }
            }
        }
        KIND_GLOB => {
            let value: f64 = raw + 1.0;
            if signed { (value * 2.0) - 1.0 } else { value }
        }
        _ => {
            let value: f64 = unsigned(raw);
            if signed { (value * 2.0) - 1.0 } else { value }
        }
    }
}

fn gradient2(node: &Node, xs: &[f64], ys: &[f64], out: &mut [f64]) {
    if node.kind == KIND_SIMPLEX {
        batch::simplex2(node.seed, xs, ys, out);
    } else {
        batch::perlin2(node.seed, node.longerp, xs, ys, out);
    }
}

fn gradient3(node: &Node, xs: &[f64], ys: &[f64], zs: &[f64], out: &mut [f64]) {
    if node.kind == KIND_SIMPLEX {
        batch::simplex3(node.seed, xs, ys, zs, out);
    } else {
        batch::perlin3(node.seed, node.longerp, xs, ys, zs, out);
    }
}

fn octave2(node: &Node, signed: bool, work: &mut Work, out: &mut [f64], length: usize) {
    let ox: f64 = node.ox;
    let oz: f64 = node.oz;
    let frequency: f64 = node.frequency;
    let Work { u, v, a, b, sum, n, .. } = work;
    let u: &[f64] = &u[..length];
    let v: &[f64] = &v[..length];
    let xs: &mut [f64] = &mut a[..length];
    let ys: &mut [f64] = &mut b[..length];
    if node.octaves == 1 {
        for index in 0..length {
            xs[index] = (u[index] + ox) * frequency;
            ys[index] = (v[index] + oz) * frequency;
        }
        gradient2(node, xs, ys, out);
        if !signed {
            for value in out.iter_mut() {
                *value = unsigned(*value);
            }
        }
        return;
    }
    let sum: &mut [f64] = &mut sum[..length];
    let sample: &mut [f64] = &mut n[..length];
    sum.fill(0.0);
    let mut scale: f64 = 1.0;
    let mut amplitude: f64 = 1.0;
    for _ in 0..node.octaves {
        for index in 0..length {
            xs[index] = ((u[index] + ox) * scale) * frequency;
            ys[index] = ((v[index] + oz) * scale) * frequency;
        }
        gradient2(node, xs, ys, sample);
        for index in 0..length {
            sum[index] += sample[index] * amplitude;
        }
        scale *= 2.0;
        amplitude *= 0.5;
    }
    let bounding: f64 = node.octave_bounding;
    for index in 0..length {
        let value: f64 = sum[index] * bounding;
        out[index] = if signed { value } else { unsigned(value) };
    }
}

fn octave3(node: &Node, signed: bool, work: &mut Work, out: &mut [f64], length: usize) {
    let ox: f64 = node.ox;
    let oz: f64 = node.oz;
    let frequency: f64 = node.frequency;
    let Work { u, v, w, a, b, c, sum, n } = work;
    let u: &[f64] = &u[..length];
    let v: &[f64] = &v[..length];
    let w: &[f64] = &w[..length];
    let xs: &mut [f64] = &mut a[..length];
    let ys: &mut [f64] = &mut b[..length];
    let zs: &mut [f64] = &mut c[..length];
    if node.octaves == 1 {
        for index in 0..length {
            xs[index] = (u[index] + ox) * frequency;
            ys[index] = v[index] * frequency;
            zs[index] = (w[index] + oz) * frequency;
        }
        gradient3(node, xs, ys, zs, out);
        if !signed {
            for value in out.iter_mut() {
                *value = unsigned(*value);
            }
        }
        return;
    }
    let sum: &mut [f64] = &mut sum[..length];
    let sample: &mut [f64] = &mut n[..length];
    sum.fill(0.0);
    let mut scale: f64 = 1.0;
    let mut amplitude: f64 = 1.0;
    for _ in 0..node.octaves {
        for index in 0..length {
            xs[index] = ((u[index] + ox) * scale) * frequency;
            ys[index] = (v[index] * scale) * frequency;
            zs[index] = ((w[index] + oz) * scale) * frequency;
        }
        gradient3(node, xs, ys, zs, sample);
        for index in 0..length {
            sum[index] += sample[index] * amplitude;
        }
        scale *= 2.0;
        amplitude *= 0.5;
    }
    let bounding: f64 = node.octave_bounding;
    for index in 0..length {
        let value: f64 = sum[index] * bounding;
        out[index] = if signed { value } else { unsigned(value) };
    }
}

#[inline(always)]
fn fractal_wrap(node: &Node, signed: bool, fractal: f64) -> f64 {
    match node.kind {
        KIND_FBM_SIMPLEX => {
            if signed { fractal } else { unsigned(fractal) }
        }
        KIND_RIGID_MULTI_SIMPLEX => {
            let value: f64 = 1.0 + (fractal - 1.0) * node.fractal_bounding;
            if signed { (value * 2.0) - 1.0 } else { value }
        }
        _ => {
            let value: f64 = unsigned(fractal);
            if signed { (value * 2.0) - 1.0 } else { value }
        }
    }
}

fn fractal2(node: &Node, signed: bool, work: &mut Work, out: &mut [f64], length: usize) {
    let ox: f64 = node.ox;
    let oz: f64 = node.oz;
    let frequency: f64 = node.frequency;
    let lacunarity: f64 = node.lacunarity;
    let gain: f64 = node.gain;
    let Work { u, v, a, b, sum, n, .. } = work;
    let u: &[f64] = &u[..length];
    let v: &[f64] = &v[..length];
    let xs: &mut [f64] = &mut a[..length];
    let ys: &mut [f64] = &mut b[..length];
    let sum: &mut [f64] = &mut sum[..length];
    let sample: &mut [f64] = &mut n[..length];
    let mut seed: i64 = node.seed;
    let fractal_type: i32 = node.fractal_type;
    for index in 0..length {
        xs[index] = (u[index] + ox) * frequency;
        ys[index] = (v[index] + oz) * frequency;
    }
    batch::simplex2(seed, xs, ys, sample);
    first_octave(fractal_type, sample, sum);
    let mut amplitude: f64 = 1.0;
    for _ in 1..node.octaves {
        amplitude *= gain;
        seed = seed.wrapping_add(1);
        for index in 0..length {
            xs[index] *= lacunarity;
            ys[index] *= lacunarity;
        }
        batch::simplex2(seed, xs, ys, sample);
        next_octave(fractal_type, amplitude, sample, sum);
    }
    finish_fractal(node, signed, sum, out);
}

fn fractal3(node: &Node, signed: bool, work: &mut Work, out: &mut [f64], length: usize) {
    let ox: f64 = node.ox;
    let oz: f64 = node.oz;
    let frequency: f64 = node.frequency;
    let lacunarity: f64 = node.lacunarity;
    let gain: f64 = node.gain;
    let Work { u, v, w, a, b, c, sum, n } = work;
    let u: &[f64] = &u[..length];
    let v: &[f64] = &v[..length];
    let w: &[f64] = &w[..length];
    let xs: &mut [f64] = &mut a[..length];
    let ys: &mut [f64] = &mut b[..length];
    let zs: &mut [f64] = &mut c[..length];
    let sum: &mut [f64] = &mut sum[..length];
    let sample: &mut [f64] = &mut n[..length];
    let mut seed: i64 = node.seed;
    let fractal_type: i32 = node.fractal_type;
    for index in 0..length {
        xs[index] = (u[index] + ox) * frequency;
        ys[index] = v[index] * frequency;
        zs[index] = (w[index] + oz) * frequency;
    }
    batch::simplex3(seed, xs, ys, zs, sample);
    first_octave(fractal_type, sample, sum);
    let mut amplitude: f64 = 1.0;
    for _ in 1..node.octaves {
        amplitude *= gain;
        seed = seed.wrapping_add(1);
        for index in 0..length {
            xs[index] *= lacunarity;
            ys[index] *= lacunarity;
            zs[index] *= lacunarity;
        }
        batch::simplex3(seed, xs, ys, zs, sample);
        next_octave(fractal_type, amplitude, sample, sum);
    }
    finish_fractal(node, signed, sum, out);
}

fn first_octave(fractal_type: i32, sample: &[f64], sum: &mut [f64]) {
    match fractal_type {
        FRACTAL_FBM => sum.copy_from_slice(sample),
        FRACTAL_BILLOW => {
            for (target, value) in sum.iter_mut().zip(sample) {
                *target = value.abs() * 2.0 - 1.0;
            }
        }
        _ => {
            for (target, value) in sum.iter_mut().zip(sample) {
                *target = 1.0 - value.abs();
            }
        }
    }
}

fn next_octave(fractal_type: i32, amplitude: f64, sample: &[f64], sum: &mut [f64]) {
    match fractal_type {
        FRACTAL_FBM => {
            for (target, value) in sum.iter_mut().zip(sample) {
                *target += value * amplitude;
            }
        }
        FRACTAL_BILLOW => {
            for (target, value) in sum.iter_mut().zip(sample) {
                *target += (value.abs() * 2.0 - 1.0) * amplitude;
            }
        }
        _ => {
            for (target, value) in sum.iter_mut().zip(sample) {
                *target -= (1.0 - value.abs()) * amplitude;
            }
        }
    }
}

fn finish_fractal(node: &Node, signed: bool, sum: &[f64], out: &mut [f64]) {
    let bounded: bool = node.fractal_type == FRACTAL_FBM || node.fractal_type == FRACTAL_BILLOW;
    let bounding: f64 = node.fractal_bounding;
    for (target, value) in out.iter_mut().zip(sum) {
        let fractal: f64 = if bounded { value * bounding } else { *value };
        *target = fractal_wrap(node, signed, fractal);
    }
}

#[cfg(test)]
mod tests {
    use super::{Header, Node, Term};

    #[test]
    fn descriptor_layouts_match_the_java_encoder() {
        assert_eq!(size_of::<Header>(), 48);
        assert_eq!(size_of::<Node>(), 160);
        assert_eq!(size_of::<Term>(), 48);
        assert_eq!(std::mem::offset_of!(Node, seed), 40);
        assert_eq!(std::mem::offset_of!(Node, patch), 152);
        assert_eq!(std::mem::offset_of!(Term, child_count), 44);
    }
}
