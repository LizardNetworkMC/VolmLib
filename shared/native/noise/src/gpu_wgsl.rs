use std::fmt::Write;

use crate::kernels::{
    DISTANCE_EUCLIDEAN, DISTANCE_MANHATTAN, DISTANCE_NATURAL, FRACTAL_BILLOW, FRACTAL_FBM, GRAD2_X, GRAD2_Y, GRAD3_X, GRAD3_Y, GRAD3_Z,
    LONGERP_HERMITE, LONGERP_LINEAR, LONGERP_QULONGIC, RETURN_CELL_VALUE, RETURN_DISTANCE, RETURN_DISTANCE2, RETURN_DISTANCE2_ADD,
    RETURN_DISTANCE2_DIV, RETURN_DISTANCE2_MUL, RETURN_DISTANCE2_SUB, RETURN_NOISE_LOOKUP,
};
use crate::program::{
    Node, Program, Term, KIND_BILLOW_SIMPLEX, KIND_CELLULAR, KIND_FBM_SIMPLEX, KIND_GLOB, KIND_PERLIN, KIND_RIGID_MULTI_SIMPLEX,
    KIND_SIMPLEX, KIND_VASCULAR, KIND_WHITE, MODE_SIGNED_2D, MODE_SIGNED_3D, MODE_UNSIGNED_3D,
};

pub const RECORD_WORDS: usize = 12;
pub const WORKGROUP: u32 = 64;
pub const SENTINEL: f32 = 3.0e38;
const KERNEL_ERROR: f64 = 2.0e-6;
const SEAM_JUMP: f64 = 4.0e-3;
const JUMP_TOLERANCE: f64 = 5.0e-4;
const FRACTION_ERROR: f64 = 5.960464477539063e-8;
const RELATIVE_ERROR: f64 = 1.1920928955078125e-7;

const INJECT_ADD: i32 = 0;
const INJECT_SRC_SUBTRACT: i32 = 1;
const INJECT_DST_SUBTRACT: i32 = 2;
const INJECT_MULTIPLY: i32 = 3;
const INJECT_MAX: i32 = 4;
const INJECT_MIN: i32 = 5;

#[derive(Clone, Copy)]
pub struct RecordSpec {
    pub slots: usize,
    pub axes: [usize; 3],
    pub offsets: [f64; 3],
    pub scale: f64,
    pub frequency: f64,
    pub multiplier: f64,
    pub fractal: bool,
    pub lacunarity_steps: u32,
    pub lacunarity: f64,
    pub skew: bool,
}

pub struct Specialized {
    pub source: String,
    pub records: Vec<RecordSpec>,
    pub volumetric: bool,
    pub uses_cells: bool,
}

impl RecordSpec {
    pub fn write(&self, origin: &[f64; 3], words: &mut [u32]) -> bool {
        let mut noise: [f64; 3] = [0.0; 3];
        for slot in 0..self.slots {
            let base: f64 = origin[self.axes[slot]] * self.scale + self.offsets[slot];
            noise[slot] = if self.fractal {
                let mut value: f64 = base * self.frequency;
                for _ in 0..self.lacunarity_steps {
                    value *= self.lacunarity;
                }
                value
            } else {
                (base * self.multiplier) * self.frequency
            };
        }
        if self.skew {
            let skew: f64 = if self.slots == 2 {
                (noise[0] + noise[1]) * crate::kernels::F2
            } else {
                (noise[0] + noise[1] + noise[2]) * crate::kernels::F3
            };
            for value in noise.iter_mut().take(self.slots) {
                *value += skew;
            }
        }
        for slot in 0..3 {
            let value: f64 = if slot < self.slots { noise[slot] } else { 0.0 };
            if !value.is_finite() || value.abs() > 4.0e18 {
                return false;
            }
            let floor: f64 = value.floor();
            let integer: i64 = floor as i64;
            words[slot] = integer as u32;
            words[4 + slot] = ((value - floor) as f32).to_bits();
            words[8 + slot] = (integer >> 32) as u32;
        }
        words[3] = 0;
        words[7] = 0;
        words[11] = 0;
        true
    }
}

pub fn supported(program: &Program) -> bool {
    for node in program.nodes {
        if node.kind == KIND_WHITE || node.kind < KIND_SIMPLEX || node.kind > KIND_GLOB {
            return false;
        }
        if matches!(node.kind, KIND_CELLULAR | KIND_VASCULAR | KIND_GLOB) && node.cell_return == RETURN_NOISE_LOOKUP {
            return false;
        }
        let constants: [f64; 13] = [
            node.frequency,
            node.ox,
            node.oz,
            node.octave_bounding,
            node.lacunarity,
            node.gain,
            node.fractal_bounding,
            node.scale,
            node.fracture_scale,
            node.signed_fracture_scale,
            node.opacity,
            node.up,
            node.down,
        ];
        if constants.iter().any(|value| !value.is_finite()) || !node.patch.is_finite() {
            return false;
        }
    }
    for term in program.terms {
        if term.child_count > 0 {
            let injector: i32 = program.nodes[term.node as usize].injector;
            if !matches!(injector, INJECT_ADD | INJECT_SRC_SUBTRACT | INJECT_DST_SUBTRACT | INJECT_MULTIPLY | INJECT_MAX | INJECT_MIN) {
                return false;
            }
            if divisor(program, term).abs() < 1.0e-12 {
                return false;
            }
        }
    }
    true
}

fn lit(value: f64) -> String {
    let single: f32 = value as f32;
    if single.is_sign_negative() {
        format!("({single:?}f)")
    } else {
        format!("{single:?}f")
    }
}

fn signed_mode(term: &Term) -> bool {
    term.mode == MODE_SIGNED_2D || term.mode == MODE_SIGNED_3D
}

fn simplex_family(kind: i32) -> bool {
    matches!(kind, KIND_SIMPLEX | KIND_BILLOW_SIMPLEX | KIND_FBM_SIMPLEX | KIND_RIGID_MULTI_SIMPLEX)
}

fn fractal(kind: i32) -> bool {
    matches!(kind, KIND_BILLOW_SIMPLEX | KIND_FBM_SIMPLEX | KIND_RIGID_MULTI_SIMPLEX)
}

fn cellular(kind: i32) -> bool {
    matches!(kind, KIND_CELLULAR | KIND_VASCULAR | KIND_GLOB)
}

struct TermAnalysis {
    flagged: Vec<bool>,
    factors: Vec<f64>,
    fracture: f64,
    slope: f64,
    base: f64,
    output: f64,
    share: f64,
}

struct Octaves {
    factors: Vec<f64>,
    amplitudes: Vec<f64>,
    bounding: f64,
}

fn lipschitz(node: &Node) -> f64 {
    match node.kind {
        KIND_PERLIN => 3.0,
        KIND_GLOB => 16.0,
        KIND_CELLULAR | KIND_VASCULAR => {
            if node.cell_return == RETURN_CELL_VALUE {
                0.0
            } else {
                8.0
            }
        }
        _ => 7.0,
    }
}

fn mapping(node: &Node, signed: bool) -> f64 {
    let base: f64 = match node.kind {
        KIND_BILLOW_SIMPLEX | KIND_GLOB => 1.0,
        KIND_RIGID_MULTI_SIMPLEX => 1.0,
        _ => 0.5,
    };
    if signed { base * 2.0 } else { base }
}

fn octaves(node: &Node) -> Octaves {
    let mut factors: Vec<f64> = Vec::new();
    let mut amplitudes: Vec<f64> = Vec::new();
    if cellular(node.kind) {
        factors.push(node.scale * node.frequency);
        amplitudes.push(1.0);
        return Octaves { factors, amplitudes, bounding: 1.0 };
    }
    if fractal(node.kind) {
        let mut factor: f64 = node.scale * node.frequency;
        let mut amplitude: f64 = 1.0;
        for octave in 0..node.octaves {
            if octave > 0 {
                amplitude *= node.gain;
            }
            factors.push(factor);
            amplitudes.push(amplitude);
            factor *= node.lacunarity;
        }
        return Octaves { factors, amplitudes, bounding: node.fractal_bounding.abs() };
    }
    let mut multiplier: f64 = 1.0;
    let mut amplitude: f64 = 1.0;
    for _ in 0..node.octaves {
        factors.push(node.scale * multiplier * node.frequency);
        amplitudes.push(amplitude);
        multiplier *= 2.0;
        amplitude *= 0.5;
    }
    let bounding: f64 = if node.octaves == 1 { 1.0 } else { node.octave_bounding.abs() };
    Octaves { factors, amplitudes, bounding }
}

fn divisor(program: &Program, term: &Term) -> f64 {
    let weight: f64 = match program.nodes[term.node as usize].injector {
        INJECT_ADD => 1.0,
        INJECT_SRC_SUBTRACT | INJECT_DST_SUBTRACT => -1.0,
        _ => 0.0,
    };
    1.0 + weight * term.child_count as f64
}

fn combines(term: &Term) -> bool {
    !signed_mode(term) && term.apply_post != 0 && term.child_count > 0
}

fn child_share(program: &Program, term: &Term, node: &Node) -> f64 {
    if combines(term) { node.patch.abs() * 2.0 / divisor(program, term).abs() } else { 0.0 }
}

fn post_factor(program: &Program, term: &Term, node: &Node) -> f64 {
    if signed_mode(term) || term.apply_post == 0 {
        return 1.0;
    }
    if term.child_count > 0 { child_share(program, term, node) } else { node.patch.abs() }
}

fn derived(term: &Term, node: &Node) -> bool {
    signed_mode(term) && node.identity_signed == 0
}

fn fracture(term: &Term, node: &Node) -> f64 {
    if term.dep0 < 0 {
        return 0.0;
    }
    if signed_mode(term) { node.signed_fracture_scale.abs() } else { node.fracture_scale.abs() }
}

fn analyze(program: &Program) -> Vec<TermAnalysis> {
    let count: usize = program.terms.len();
    let mut shapes: Vec<Option<Octaves>> = Vec::with_capacity(count);
    let mut slopes: Vec<f64> = vec![0.0; count];
    let mut outputs: Vec<f64> = vec![0.0; count];
    let mut sensitivities: Vec<f64> = vec![0.0; count];
    for term in program.terms {
        let node: &Node = &program.nodes[term.node as usize];
        if derived(term, node) {
            shapes.push(None);
            continue;
        }
        let shape: Octaves = octaves(node);
        let index: usize = shapes.len();
        outputs[index] = post_factor(program, term, node) * node.opacity.abs() * mapping(node, signed_mode(term)) * shape.bounding;
        let reach: f64 = shape.factors.iter().zip(&shape.amplitudes).map(|(factor, amplitude)| factor.abs() * amplitude).sum::<f64>();
        slopes[index] = reach * lipschitz(node);
        sensitivities[index] = reach * lipschitz(node).max(16.0 * f64::from(u8::from(cellular(node.kind))));
        shapes.push(Some(shape));
    }
    let mut alpha: Vec<f64> = vec![0.0; count];
    alpha[count - 1] = 1.0;
    for index in (0..count).rev() {
        let term: &Term = &program.terms[index];
        let node: &Node = &program.nodes[term.node as usize];
        if derived(term, node) {
            alpha[term.dep0 as usize] += 2.0 * alpha[index];
            continue;
        }
        let displacement: f64 = alpha[index] * outputs[index] * sensitivities[index] * fracture(term, node);
        let slots: usize = if term.mode >= MODE_UNSIGNED_3D { 3 } else { 2 };
        for dependency in [term.dep0, term.dep1, term.dep2].iter().take(slots) {
            if *dependency >= 0 {
                alpha[*dependency as usize] += displacement;
            }
        }
        if combines(term) {
            let start: usize = term.child_start as usize;
            let share: f64 = alpha[index] * child_share(program, term, node);
            for child in &program.children[start..start + term.child_count as usize] {
                alpha[*child as usize] += share;
            }
        }
    }
    let mut result: Vec<TermAnalysis> = Vec::with_capacity(count);
    for (index, term) in program.terms.iter().enumerate() {
        let node: &Node = &program.nodes[term.node as usize];
        let Some(shape) = &shapes[index] else {
            result.push(TermAnalysis { flagged: Vec::new(), factors: Vec::new(), fracture: 0.0, slope: 0.0, base: 0.0, output: 0.0, share: 0.0 });
            continue;
        };
        let seams: bool = term.mode >= MODE_UNSIGNED_3D && simplex_family(node.kind);
        let mut flagged: Vec<bool> = Vec::with_capacity(shape.factors.len());
        let mut base: f64 = 0.0;
        for amplitude in &shape.amplitudes {
            let flag: bool = seams && alpha[index] * outputs[index] * amplitude * SEAM_JUMP > JUMP_TOLERANCE;
            base += amplitude * (lipschitz(node) * FRACTION_ERROR + KERNEL_ERROR + if seams && !flag { SEAM_JUMP } else { 0.0 });
            flagged.push(flag);
        }
        result.push(TermAnalysis {
            flagged,
            factors: shape.factors.clone(),
            fracture: fracture(term, node),
            slope: slopes[index],
            base,
            output: outputs[index],
            share: child_share(program, term, node),
        });
    }
    result
}

pub fn specialize(program: &Program) -> Option<Specialized> {
    if !supported(program) {
        return None;
    }
    let volumetric: bool = program.header.volumetric != 0;
    let mut records: Vec<RecordSpec> = Vec::new();
    let mut body: String = String::new();
    let mut perlin_modes: [bool; 3] = [false; 3];
    let mut cell_variants: Vec<(usize, i32, i32)> = Vec::new();
    let analysis: Vec<TermAnalysis> = analyze(program);
    for (index, term) in program.terms.iter().enumerate() {
        let node: &Node = &program.nodes[term.node as usize];
        let flags: &TermAnalysis = &analysis[index];
        let signed: bool = signed_mode(term);
        let volume: bool = term.mode >= MODE_UNSIGNED_3D;
        if volume && !volumetric {
            return None;
        }
        if signed && node.identity_signed == 0 {
            writeln!(body, "    let v{index}: f32 = v{} * 2.0 - 1.0;", term.dep0).ok()?;
            writeln!(body, "    let err{index}: f32 = 2.0 * err{};", term.dep0).ok()?;
            continue;
        }
        let slots: usize = if volume { 3 } else { 2 };
        let axes: [i32; 3] = [term.axis0, term.axis1, term.axis2];
        let deps: [i32; 3] = [term.dep0, term.dep1, term.dep2];
        if term.dep0 < 0 {
            writeln!(body, "    let d{index}: f32 = 0.0;").ok()?;
        } else {
            let worst: String = deps[..slots].iter().map(|dependency| format!("err{dependency}")).reduce(|left, right| format!("max({left}, {right})"))?;
            writeln!(body, "    let d{index}: f32 = {} * {worst};", lit(flags.fracture)).ok()?;
        }
        for slot in 0..slots {
            let axis: i32 = axes[slot];
            let expression: String = if term.dep0 < 0 {
                format!("p{axis}")
            } else if signed {
                format!("(p{axis} + v{} * {})", deps[slot], lit(node.signed_fracture_scale))
            } else {
                format!("(p{axis} + (v{} - 0.5) * {})", deps[slot], lit(node.fracture_scale))
            };
            writeln!(body, "    let q{index}_{slot}: f32 = {expression};").ok()?;
        }
        let magnitude: String = (0..slots).map(|slot| format!("abs(q{index}_{slot})")).collect::<Vec<String>>().join(" + ");
        writeln!(body, "    let s{index}: f32 = {magnitude};").ok()?;
        let offsets: [f64; 3] = if volume { [node.ox, 0.0, node.oz] } else { [node.ox, node.oz, 0.0] };
        let axes_usize: [usize; 3] = [axes[0] as usize, axes[1] as usize, if volume { axes[2] as usize } else { 0 }];
        let base: usize = records.len();
        let seed: u32 = node.seed as u32;
        let raw: String = format!("r{index}");
        if cellular(node.kind) {
            records.push(RecordSpec {
                slots,
                axes: axes_usize,
                offsets,
                scale: node.scale,
                frequency: node.frequency,
                multiplier: 1.0,
                fractal: false,
                lacunarity_steps: 0,
                lacunarity: 1.0,
                skew: false,
            });
            let factor: String = lit(node.scale * node.frequency);
            let variant: (usize, i32, i32) = (slots, node.cell_distance, node.cell_return);
            if !cell_variants.contains(&variant) {
                cell_variants.push(variant);
            }
            writeln!(
                body,
                "    let {raw}: f32 = cell{slots}_{}_{}({seed}u, {base}u, {}, {});",
                node.cell_distance,
                node.cell_return,
                scaled_arguments(index, slots, &factor),
                slack(index, flags.factors[0])
            )
            .ok()?;
            writeln!(body, "    var v{index}: f32 = {};", cellular_wrap(node.kind, signed, &raw)).ok()?;
        } else if fractal(node.kind) {
            let function: &str = if volume { "simplex3" } else { "simplex2" };
            let mut factor: f64 = node.scale * node.frequency;
            let mut amplitude: f64 = 1.0;
            writeln!(body, "    var {raw}: f32;").ok()?;
            for octave in 0..node.octaves {
                records.push(RecordSpec {
                    slots,
                    axes: axes_usize,
                    offsets,
                    scale: node.scale,
                    frequency: node.frequency,
                    multiplier: 1.0,
                    fractal: true,
                    lacunarity_steps: octave as u32,
                    lacunarity: node.lacunarity,
                    skew: true,
                });
                let octave_seed: u32 = (node.seed.wrapping_add(octave as i64)) as u32;
                let sample: String = seam_call(
                    index,
                    function,
                    flags,
                    octave as usize,
                    format!("{octave_seed}u, {}u, {}", base + octave as usize, scaled_arguments(index, slots, &lit(factor))),
                );
                if octave == 0 {
                    let first: String = match node.fractal_type {
                        FRACTAL_FBM => sample,
                        FRACTAL_BILLOW => format!("abs({sample}) * 2.0 - 1.0"),
                        _ => format!("1.0 - abs({sample})"),
                    };
                    writeln!(body, "    {raw} = {first};").ok()?;
                } else {
                    amplitude *= node.gain;
                    let next: String = match node.fractal_type {
                        FRACTAL_FBM => format!("{raw} + {sample} * {}", lit(amplitude)),
                        FRACTAL_BILLOW => format!("{raw} + (abs({sample}) * 2.0 - 1.0) * {}", lit(amplitude)),
                        _ => format!("{raw} - (1.0 - abs({sample})) * {}", lit(amplitude)),
                    };
                    writeln!(body, "    {raw} = {next};").ok()?;
                }
                factor *= node.lacunarity;
            }
            let bounded: bool = node.fractal_type == FRACTAL_FBM || node.fractal_type == FRACTAL_BILLOW;
            let value: String = if bounded { format!("{raw} * {}", lit(node.fractal_bounding)) } else { raw.clone() };
            let wrapped: String = match node.kind {
                KIND_FBM_SIMPLEX => {
                    if signed {
                        value
                    } else {
                        format!("({value}) / 2.0 + 0.5")
                    }
                }
                KIND_RIGID_MULTI_SIMPLEX => {
                    let rigid: String = format!("(1.0 + (({value}) - 1.0) * {})", lit(node.fractal_bounding));
                    if signed { format!("{rigid} * 2.0 - 1.0") } else { rigid }
                }
                _ => {
                    let unsigned: String = format!("(({value}) / 2.0 + 0.5)");
                    if signed { format!("{unsigned} * 2.0 - 1.0") } else { unsigned }
                }
            };
            writeln!(body, "    var v{index}: f32 = {wrapped};").ok()?;
        } else {
            let function: String = if node.kind == KIND_SIMPLEX {
                if volume { "simplex3".to_string() } else { "simplex2".to_string() }
            } else {
                let mode: usize = node.longerp.clamp(LONGERP_LINEAR, LONGERP_QULONGIC) as usize;
                perlin_modes[mode] = true;
                if volume { format!("perlin3_{mode}") } else { format!("perlin2_{mode}") }
            };
            let skew: bool = simplex_family(node.kind);
            if node.octaves == 1 {
                records.push(RecordSpec {
                    slots,
                    axes: axes_usize,
                    offsets,
                    scale: node.scale,
                    frequency: node.frequency,
                    multiplier: 1.0,
                    fractal: false,
                    lacunarity_steps: 0,
                    lacunarity: 1.0,
                    skew,
                });
                let sample: String = seam_call(
                    index,
                    &function,
                    flags,
                    0,
                    format!("{seed}u, {base}u, {}", scaled_arguments(index, slots, &lit(node.scale * node.frequency))),
                );
                let value: String = if signed { sample } else { format!("{sample} / 2.0 + 0.5") };
                writeln!(body, "    var v{index}: f32 = {value};").ok()?;
            } else {
                writeln!(body, "    var {raw}: f32 = 0.0;").ok()?;
                let mut multiplier: f64 = 1.0;
                let mut amplitude: f64 = 1.0;
                for octave in 0..node.octaves {
                    records.push(RecordSpec {
                        slots,
                        axes: axes_usize,
                        offsets,
                        scale: node.scale,
                        frequency: node.frequency,
                        multiplier,
                        fractal: false,
                        lacunarity_steps: 0,
                        lacunarity: 1.0,
                        skew,
                    });
                    let sample: String = seam_call(
                        index,
                        &function,
                        flags,
                        octave as usize,
                        format!(
                            "{seed}u, {}u, {}",
                            base + octave as usize,
                            scaled_arguments(index, slots, &lit(node.scale * multiplier * node.frequency))
                        ),
                    );
                    writeln!(body, "    {raw} = {raw} + {sample} * {};", lit(amplitude)).ok()?;
                    multiplier *= 2.0;
                    amplitude *= 0.5;
                }
                let value: String = format!("{raw} * {}", lit(node.octave_bounding));
                let mapped: String = if signed { value } else { format!("({value}) / 2.0 + 0.5") };
                writeln!(body, "    var v{index}: f32 = {mapped};").ok()?;
            }
        }
        if signed {
            if node.unity_opacity == 0 {
                writeln!(body, "    v{index} = ((v{index} + 1.0) * {}) - 1.0;", lit(node.opacity)).ok()?;
            }
        } else {
            writeln!(body, "    v{index} = v{index} * {};", lit(node.opacity)).ok()?;
            if term.apply_post != 0 {
                post(program, term, node, index, &mut body)?;
            }
        }
        writeln!(
            body,
            "    var err{index}: f32 = {} * ((d{index} + {} * s{index}) * {} + {});",
            lit(flags.output),
            lit(RELATIVE_ERROR),
            lit(flags.slope),
            lit(flags.base)
        )
        .ok()?;
        if combines(term) {
            let start: usize = term.child_start as usize;
            for child in &program.children[start..start + term.child_count as usize] {
                writeln!(body, "    err{index} = err{index} + {} * err{child};", lit(flags.share)).ok()?;
            }
        }
    }
    let root: usize = program.terms.len() - 1;
    let uses_cells: bool = !cell_variants.is_empty();
    let mut source: String = prelude(uses_cells);
    for (mode, used) in perlin_modes.iter().enumerate() {
        if *used {
            source.push_str(&perlin_functions(mode as i32));
        }
    }
    for (slots, distance, result) in &cell_variants {
        source.push_str(&cell_function(*slots, *distance, *result));
    }
    source.push_str("@compute @workgroup_size(64)\nfn main(@builtin(global_invocation_id) gid: vec3<u32>) {\n");
    source.push_str("    let id: u32 = gid.x + gid.y * dims.row;\n    if (id >= dims.total) {\n        return;\n    }\n    ambiguous = false;\n");
    if volumetric {
        source.push_str(
            "    let ix: u32 = id % dims.nx;\n    let rest: u32 = id / dims.nx;\n    let iz: u32 = rest % dims.nz;\n    let iy: u32 = rest / dims.nz;\n",
        );
        source.push_str("    let p0: f32 = (f32(ix) - dims.cx) * dims.sx;\n    let p1: f32 = (f32(iy) - dims.cy) * dims.sy;\n    let p2: f32 = (f32(iz) - dims.cz) * dims.sz;\n");
    } else {
        source.push_str("    let ix: u32 = id % dims.nx;\n    let iz: u32 = id / dims.nx;\n");
        source.push_str("    let p0: f32 = (f32(ix) - dims.cx) * dims.sx;\n    let p1: f32 = (f32(iz) - dims.cz) * dims.sz;\n");
    }
    source.push_str(&body);
    writeln!(source, "    output[id] = select(v{root}, {}, ambiguous);\n}}", lit(SENTINEL as f64)).ok()?;
    Some(Specialized { source, records, volumetric, uses_cells })
}

fn slack(index: usize, factor: f64) -> String {
    format!("(d{index} * {} + {})", lit(factor.abs()), lit(FRACTION_ERROR))
}

fn seam_call(index: usize, function: &str, flags: &TermAnalysis, octave: usize, arguments: String) -> String {
    if function == "simplex3" && flags.flagged[octave] {
        format!("simplex3e({arguments}, {})", slack(index, flags.factors[octave]))
    } else {
        format!("{function}({arguments})")
    }
}

fn scaled_arguments(index: usize, slots: usize, factor: &str) -> String {
    let mut arguments: Vec<String> = Vec::with_capacity(slots);
    for slot in 0..slots {
        arguments.push(format!("q{index}_{slot} * {factor}"));
    }
    arguments.join(", ")
}

fn cellular_wrap(kind: i32, signed: bool, raw: &str) -> String {
    match kind {
        KIND_VASCULAR => {
            if signed {
                format!("clamp(-{raw}, -1.0, 1.0)")
            } else {
                format!("clamp(0.5 - {raw} * 0.5, 0.0, 1.0)")
            }
        }
        KIND_GLOB => {
            if signed {
                format!("(({raw} + 1.0) * 2.0 - 1.0)")
            } else {
                format!("({raw} + 1.0)")
            }
        }
        _ => {
            if signed {
                format!("(({raw} / 2.0 + 0.5) * 2.0 - 1.0)")
            } else {
                format!("({raw} / 2.0 + 0.5)")
            }
        }
    }
}

fn post(program: &Program, term: &Term, node: &Node, index: usize, body: &mut String) -> Option<()> {
    let start: usize = term.child_start as usize;
    let children: &[i32] = &program.children[start..start + term.child_count as usize];
    let tail: String = format!("- {} + {}) * {}", lit(node.down), lit(node.up), lit(node.patch));
    if children.is_empty() {
        writeln!(body, "    v{index} = ((v{index} / 1.0) {tail};").ok()?;
        return Some(());
    }
    let weight: f64 = match node.injector {
        INJECT_ADD => 1.0,
        INJECT_SRC_SUBTRACT | INJECT_DST_SUBTRACT => -1.0,
        _ => 0.0,
    };
    let mut divisor: f64 = 1.0;
    writeln!(body, "    var n{index}: f32 = v{index};").ok()?;
    for child in children {
        let source: String = format!("n{index}");
        let value: String = format!("v{child}");
        let combined: String = match node.injector {
            INJECT_ADD => format!("{source} + {value}"),
            INJECT_SRC_SUBTRACT => format!("select({source} - {value}, 0.0, {source} - {value} < 0.0)"),
            INJECT_DST_SUBTRACT => format!("select({source} - {value}, 0.0, {value} - {source} < 0.0)"),
            INJECT_MULTIPLY => format!("{source} * {value}"),
            INJECT_MAX => format!("max({source}, {value})"),
            _ => format!("min({source}, {value})"),
        };
        writeln!(body, "    n{index} = {combined};").ok()?;
        divisor += weight;
    }
    writeln!(body, "    v{index} = ((n{index} / {}) {tail};", lit(divisor)).ok()?;
    Some(())
}

fn prelude(uses_cells: bool) -> String {
    let mut source: String = String::with_capacity(8192);
    source.push_str(
        "struct Dims {\n    nx: u32,\n    ny: u32,\n    nz: u32,\n    total: u32,\n    sx: f32,\n    sy: f32,\n    sz: f32,\n    row: u32,\n    cx: f32,\n    cy: f32,\n    cz: f32,\n    pad: u32,\n}\n\
         @group(0) @binding(0) var<uniform> dims: Dims;\n\
         @group(0) @binding(1) var<storage, read> rec: array<vec4<u32>>;\n\
         @group(0) @binding(2) var<storage, read_write> output: array<f32>;\n",
    );
    if uses_cells {
        source.push_str("@group(0) @binding(3) var<storage, read> cells: array<vec4<f32>>;\n");
    }
    source.push_str("var<private> ambiguous: bool;\n");
    writeln!(source, "const F2: f32 = {};", lit(crate::kernels::F2)).ok();
    writeln!(source, "const G2: f32 = {};", lit(crate::kernels::G2)).ok();
    writeln!(source, "const F3: f32 = {};", lit(crate::kernels::F3)).ok();
    writeln!(source, "const G3: f32 = {};", lit(crate::kernels::G3)).ok();
    writeln!(source, "const GRAD2_X: u32 = {}u;\nconst GRAD2_Y: u32 = {}u;", GRAD2_X, GRAD2_Y).ok();
    writeln!(source, "const GRAD3_X: u32 = {}u;\nconst GRAD3_Y: u32 = {}u;\nconst GRAD3_Z: u32 = {}u;", GRAD3_X, GRAD3_Y, GRAD3_Z).ok();
    source.push_str(
        r#"
fn hash2(s: u32, x: u32, y: u32) -> u32 {
    let b: u32 = s ^ (x * 1619u) ^ (y * 31337u);
    let c: u32 = b * b * b * 60493u;
    return (c >> 13u) ^ c;
}
fn hash3(s: u32, x: u32, y: u32, z: u32) -> u32 {
    let b: u32 = s ^ (x * 1619u) ^ (y * 31337u) ^ (z * 6971u);
    let c: u32 = b * b * b * 60493u;
    return (c >> 13u) ^ c;
}
fn unpack(p: u32, i: u32) -> f32 {
    return f32(bitcast<i32>(((p >> (i << 1u)) & 3u) << 30u) >> 30u);
}
fn grad2(h: u32, x: f32, y: f32) -> f32 {
    let i: u32 = h & 7u;
    return x * unpack(GRAD2_X, i) + y * unpack(GRAD2_Y, i);
}
fn grad3(h: u32, x: f32, y: f32, z: f32) -> f32 {
    let i: u32 = h & 15u;
    return x * unpack(GRAD3_X, i) + y * unpack(GRAD3_Y, i) + z * unpack(GRAD3_Z, i);
}
fn corner(t: f32, g: f32) -> f32 {
    let q: f32 = t * t;
    return select(q * q * g, 0.0, t < 0.0);
}
fn simplex2(s: u32, r: u32, lx: f32, ly: f32) -> f32 {
    let a: vec4<u32> = rec[r * 3u];
    let b: vec4<u32> = rec[r * 3u + 1u];
    let ls: f32 = (lx + ly) * F2;
    let tx: f32 = bitcast<f32>(b.x) + (lx + ls);
    let ty: f32 = bitcast<f32>(b.y) + (ly + ls);
    let fi: f32 = floor(tx);
    let fj: f32 = floor(ty);
    let rx: f32 = tx - fi;
    let ry: f32 = ty - fj;
    let t: f32 = (rx + ry) * G2;
    let x0: f32 = rx - t;
    let y0: f32 = ry - t;
    let iu: u32 = a.x + bitcast<u32>(i32(fi));
    let ju: u32 = a.y + bitcast<u32>(i32(fj));
    let first: bool = x0 > y0;
    let x1: f32 = x0 - select(0.0, 1.0, first) + G2;
    let y1: f32 = y0 - select(1.0, 0.0, first) + G2;
    let x2: f32 = x0 - 1.0 + 2.0 * G2;
    let y2: f32 = y0 - 1.0 + 2.0 * G2;
    let n0: f32 = corner(0.5 - x0 * x0 - y0 * y0, grad2(hash2(s, iu, ju), x0, y0));
    let n1: f32 = corner(0.5 - x1 * x1 - y1 * y1, grad2(hash2(s, iu + select(0u, 1u, first), ju + select(1u, 0u, first)), x1, y1));
    let n2: f32 = corner(0.5 - x2 * x2 - y2 * y2, grad2(hash2(s, iu + 1u, ju + 1u), x2, y2));
    return 50.0 * (n0 + n1 + n2);
}
fn simplex3(s: u32, r: u32, lx: f32, ly: f32, lz: f32) -> f32 {
    let a: vec4<u32> = rec[r * 3u];
    let b: vec4<u32> = rec[r * 3u + 1u];
    let ls: f32 = (lx + ly + lz) * F3;
    let tx: f32 = bitcast<f32>(b.x) + (lx + ls);
    let ty: f32 = bitcast<f32>(b.y) + (ly + ls);
    let tz: f32 = bitcast<f32>(b.z) + (lz + ls);
    let fi: f32 = floor(tx);
    let fj: f32 = floor(ty);
    let fk: f32 = floor(tz);
    let rx: f32 = tx - fi;
    let ry: f32 = ty - fj;
    let rz: f32 = tz - fk;
    let t: f32 = (rx + ry + rz) * G3;
    let x0: f32 = rx - t;
    let y0: f32 = ry - t;
    let z0: f32 = rz - t;
    let iu: u32 = a.x + bitcast<u32>(i32(fi));
    let ju: u32 = a.y + bitcast<u32>(i32(fj));
    let ku: u32 = a.z + bitcast<u32>(i32(fk));
    let ca: bool = x0 >= y0;
    let b1: bool = y0 >= z0;
    let c1: bool = x0 >= z0;
    let b2: bool = y0 < z0;
    let c2: bool = x0 < z0;
    let i1: bool = ca && (b1 || c1);
    let j1: bool = !ca && !b2;
    let k1: bool = select(b2, !b1 && !c1, ca);
    let i2: bool = ca || (!b2 && !c2);
    let j2: bool = !ca || b1;
    let k2: bool = select(b2 || c2, !b1, ca);
    let x1: f32 = x0 - select(0.0, 1.0, i1) + G3;
    let y1: f32 = y0 - select(0.0, 1.0, j1) + G3;
    let z1: f32 = z0 - select(0.0, 1.0, k1) + G3;
    let x2: f32 = x0 - select(0.0, 1.0, i2) + F3;
    let y2: f32 = y0 - select(0.0, 1.0, j2) + F3;
    let z2: f32 = z0 - select(0.0, 1.0, k2) + F3;
    let x3: f32 = x0 - 0.5;
    let y3: f32 = y0 - 0.5;
    let z3: f32 = z0 - 0.5;
    let n0: f32 = corner(0.6 - x0 * x0 - y0 * y0 - z0 * z0, grad3(hash3(s, iu, ju, ku), x0, y0, z0));
    let n1: f32 = corner(0.6 - x1 * x1 - y1 * y1 - z1 * z1,
        grad3(hash3(s, iu + select(0u, 1u, i1), ju + select(0u, 1u, j1), ku + select(0u, 1u, k1)), x1, y1, z1));
    let n2: f32 = corner(0.6 - x2 * x2 - y2 * y2 - z2 * z2,
        grad3(hash3(s, iu + select(0u, 1u, i2), ju + select(0u, 1u, j2), ku + select(0u, 1u, k2)), x2, y2, z2));
    let n3: f32 = corner(0.6 - x3 * x3 - y3 * y3 - z3 * z3, grad3(hash3(s, iu + 1u, ju + 1u, ku + 1u), x3, y3, z3));
    return 32.0 * (n0 + n1 + n2 + n3);
}
fn simplex3e(s: u32, r: u32, lx: f32, ly: f32, lz: f32, slack: f32) -> f32 {
    let b: vec4<u32> = rec[r * 3u + 1u];
    let ls: f32 = (lx + ly + lz) * F3;
    let tx: f32 = bitcast<f32>(b.x) + (lx + ls);
    let ty: f32 = bitcast<f32>(b.y) + (ly + ls);
    let tz: f32 = bitcast<f32>(b.z) + (lz + ls);
    let rx: f32 = tx - floor(tx);
    let ry: f32 = ty - floor(ty);
    let rz: f32 = tz - floor(tz);
    let e: f32 = 2.0 * (slack + 1.1920929e-7 * (abs(lx) + abs(ly) + abs(lz)));
    if (min(rx, 1.0 - rx) < e || min(ry, 1.0 - ry) < e || min(rz, 1.0 - rz) < e || abs(rx - ry) < e || abs(ry - rz) < e || abs(rx - rz) < e) {
        ambiguous = true;
    }
    return simplex3(s, r, lx, ly, lz);
}
"#,
    );
    if uses_cells {
        source.push_str(
            r#"
fn mulwide(a: u32, b: u32) -> vec2<u32> {
    let a0: u32 = a & 65535u;
    let a1: u32 = a >> 16u;
    let b0: u32 = b & 65535u;
    let b1: u32 = b >> 16u;
    let p00: u32 = a0 * b0;
    let p01: u32 = a0 * b1;
    let p10: u32 = a1 * b0;
    let p11: u32 = a1 * b1;
    let mid: u32 = (p00 >> 16u) + (p01 & 65535u) + (p10 & 65535u);
    return vec2<u32>((p00 & 65535u) | (mid << 16u), p11 + (p01 >> 16u) + (p10 >> 16u) + (mid >> 16u));
}
fn mul64(a: vec2<u32>, b: vec2<u32>) -> vec2<u32> {
    let w: vec2<u32> = mulwide(a.x, b.x);
    return vec2<u32>(w.x, w.y + a.x * b.y + a.y * b.x);
}
fn add64(a: vec2<u32>, d: i32) -> vec2<u32> {
    let lo: u32 = a.x + bitcast<u32>(d);
    let carry: u32 = select(0u, 1u, lo < a.x);
    return vec2<u32>(lo, a.y + select(0u, 4294967295u, d < 0) + carry);
}
fn finish64(base: vec2<u32>) -> f32 {
    let cube: vec2<u32> = mul64(mul64(mul64(base, base), base), vec2<u32>(60493u, 0u));
    return (f32(bitcast<i32>(cube.y)) * 4294967296.0 + f32(cube.x)) / 9.223372e18;
}
fn measure_0(x: f32, y: f32, z: f32) -> f32 {
    return x * x + y * y + z * z;
}
fn measure_1(x: f32, y: f32, z: f32) -> f32 {
    return abs(x) + abs(y) + abs(z);
}
fn measure_2(x: f32, y: f32, z: f32) -> f32 {
    return (abs(x) + abs(y) + abs(z)) + (x * x + y * y + z * z);
}
"#,
        );
    }
    source
}

fn perlin_functions(mode: i32) -> String {
    let interpolant: &str = match mode {
        LONGERP_HERMITE => "t * t * (3.0 - 2.0 * t)",
        LONGERP_QULONGIC => "t * t * t * (t * (t * 6.0 - 15.0) + 10.0)",
        _ => "t",
    };
    format!(
        r#"
fn interp_{mode}(t: f32) -> f32 {{
    return {interpolant};
}}
fn perlin2_{mode}(s: u32, r: u32, lx: f32, ly: f32) -> f32 {{
    let a: vec4<u32> = rec[r * 3u];
    let b: vec4<u32> = rec[r * 3u + 1u];
    let tx: f32 = bitcast<f32>(b.x) + lx;
    let ty: f32 = bitcast<f32>(b.y) + ly;
    let fx: f32 = floor(tx);
    let fy: f32 = floor(ty);
    let xd0: f32 = tx - fx;
    let yd0: f32 = ty - fy;
    let xs: f32 = interp_{mode}(xd0);
    let ys: f32 = interp_{mode}(yd0);
    let xd1: f32 = xd0 - 1.0;
    let yd1: f32 = yd0 - 1.0;
    let xu: u32 = a.x + bitcast<u32>(i32(fx));
    let yu: u32 = a.y + bitcast<u32>(i32(fy));
    let xf0: f32 = mix(grad2(hash2(s, xu, yu), xd0, yd0), grad2(hash2(s, xu + 1u, yu), xd1, yd0), xs);
    let xf1: f32 = mix(grad2(hash2(s, xu, yu + 1u), xd0, yd1), grad2(hash2(s, xu + 1u, yu + 1u), xd1, yd1), xs);
    return mix(xf0, xf1, ys);
}}
fn perlin3_{mode}(s: u32, r: u32, lx: f32, ly: f32, lz: f32) -> f32 {{
    let a: vec4<u32> = rec[r * 3u];
    let b: vec4<u32> = rec[r * 3u + 1u];
    let tx: f32 = bitcast<f32>(b.x) + lx;
    let ty: f32 = bitcast<f32>(b.y) + ly;
    let tz: f32 = bitcast<f32>(b.z) + lz;
    let fx: f32 = floor(tx);
    let fy: f32 = floor(ty);
    let fz: f32 = floor(tz);
    let xd0: f32 = tx - fx;
    let yd0: f32 = ty - fy;
    let zd0: f32 = tz - fz;
    let xs: f32 = interp_{mode}(xd0);
    let ys: f32 = interp_{mode}(yd0);
    let zs: f32 = interp_{mode}(zd0);
    let xd1: f32 = xd0 - 1.0;
    let yd1: f32 = yd0 - 1.0;
    let zd1: f32 = zd0 - 1.0;
    let xu: u32 = a.x + bitcast<u32>(i32(fx));
    let yu: u32 = a.y + bitcast<u32>(i32(fy));
    let zu: u32 = a.z + bitcast<u32>(i32(fz));
    let xv: u32 = xu + 1u;
    let yv: u32 = yu + 1u;
    let zv: u32 = zu + 1u;
    let xf00: f32 = mix(grad3(hash3(s, xu, yu, zu), xd0, yd0, zd0), grad3(hash3(s, xv, yu, zu), xd1, yd0, zd0), xs);
    let xf10: f32 = mix(grad3(hash3(s, xu, yv, zu), xd0, yd1, zd0), grad3(hash3(s, xv, yv, zu), xd1, yd1, zd0), xs);
    let xf01: f32 = mix(grad3(hash3(s, xu, yu, zv), xd0, yd0, zd1), grad3(hash3(s, xv, yu, zv), xd1, yd0, zd1), xs);
    let xf11: f32 = mix(grad3(hash3(s, xu, yv, zv), xd0, yd1, zd1), grad3(hash3(s, xv, yv, zv), xd1, yd1, zd1), xs);
    return mix(mix(xf00, xf10, ys), mix(xf01, xf11, ys), zs);
}}
"#
    )
}

fn edge_expression(result: i32) -> &'static str {
    match result {
        RETURN_DISTANCE2 => "d2 - 1.0",
        RETURN_DISTANCE2_ADD => "d2 + d1 - 1.0",
        RETURN_DISTANCE2_SUB => "d2 - d1 - 1.0",
        RETURN_DISTANCE2_MUL => "d2 * d1 - 1.0",
        RETURN_DISTANCE2_DIV => "d1 / d2 - 1.0",
        _ => "0.0",
    }
}

fn cell_function(slots: usize, distance: i32, result: i32) -> String {
    let distance: i32 = distance.clamp(DISTANCE_EUCLIDEAN, DISTANCE_NATURAL);
    let single: bool = result == RETURN_CELL_VALUE || result == RETURN_DISTANCE;
    if slots == 2 {
        let tail: String = if single {
            let value: &str = if result == RETURN_CELL_VALUE {
                "finish64(mul64(add64(vec2<u32>(a.x, h.x), cx), vec2<u32>(1619u, 0u)) ^ mul64(add64(vec2<u32>(a.y, h.y), cy), vec2<u32>(31337u, 0u)))"
            } else {
                "d1 - 1.0"
            };
            let gap: String = if result == RETURN_CELL_VALUE {
                "    if (d2 - d1 < 20.0 * eps) {\n        ambiguous = true;\n    }\n".to_string()
            } else {
                String::new()
            };
            format!(
                "    if (abs(tx - rxf) > 0.5 - 2.0 * eps || abs(ty - ryf) > 0.5 - 2.0 * eps) {{\n        ambiguous = true;\n    }}\n{gap}    return {value};\n"
            )
        } else {
            format!(
                r#"    let boundary: f32 = 1.55 - max(abs(tx - rxf), abs(ty - ryf));
    if (measure_{distance}(boundary, 0.0, 0.0) < d2) {{
        for (var dx: i32 = -2; dx <= 2; dx++) {{
            let xi: i32 = xr + dx;
            let bx: f32 = f32(xi) - tx;
            let ex: f32 = max(0.0, abs(bx) - 0.45);
            if (measure_{distance}(ex, 0.0, 0.0) >= d2) {{
                continue;
            }}
            for (var dy: i32 = -2; dy <= 2; dy++) {{
                if (abs(dx) < 2 && abs(dy) < 2) {{
                    continue;
                }}
                let yi: i32 = yr + dy;
                let by: f32 = f32(yi) - ty;
                let ey: f32 = max(0.0, abs(by) - 0.45);
                if (measure_{distance}(ex, ey, 0.0) >= d2) {{
                    continue;
                }}
                let c: vec4<f32> = cells[hash2(s, a.x + bitcast<u32>(xi), a.y + bitcast<u32>(yi)) & 255u];
                let cand: f32 = measure_{distance}(bx + c.x, by + c.y, 0.0);
                if (cand < d1) {{
                    d2 = d1;
                    d1 = cand;
                }} else if (cand < d2) {{
                    d2 = cand;
                }}
            }}
        }}
    }}
    return {};
"#,
                edge_expression(result)
            )
        };
        format!(
            r#"
fn cell2_{distance}_{result}(s: u32, r: u32, lx: f32, ly: f32, slack: f32) -> f32 {{
    let eps: f32 = slack + 1.1920929e-7 * (abs(lx) + abs(ly));
    let a: vec4<u32> = rec[r * 3u];
    let b: vec4<u32> = rec[r * 3u + 1u];
    let h: vec4<u32> = rec[r * 3u + 2u];
    let tx: f32 = bitcast<f32>(b.x) + lx;
    let ty: f32 = bitcast<f32>(b.y) + ly;
    let rxf: f32 = floor(tx + 0.5);
    let ryf: f32 = floor(ty + 0.5);
    let xr: i32 = i32(rxf);
    let yr: i32 = i32(ryf);
    var d1: f32 = 999999.0;
    var d2: f32 = 999999.0;
    var cx: i32 = 0;
    var cy: i32 = 0;
    for (var dx: i32 = -1; dx <= 1; dx++) {{
        let xi: i32 = xr + dx;
        let bx: f32 = f32(xi) - tx;
        for (var dy: i32 = -1; dy <= 1; dy++) {{
            let yi: i32 = yr + dy;
            let by: f32 = f32(yi) - ty;
            let c: vec4<f32> = cells[hash2(s, a.x + bitcast<u32>(xi), a.y + bitcast<u32>(yi)) & 255u];
            let cand: f32 = measure_{distance}(bx + c.x, by + c.y, 0.0);
            let closer: bool = cand < d1;
            d2 = select(select(d2, cand, cand < d2), d1, closer);
            d1 = select(d1, cand, closer);
            cx = select(cx, xi, closer);
            cy = select(cy, yi, closer);
        }}
    }}
{tail}}}
"#
        )
    } else {
        let radius: i32 = if distance == DISTANCE_MANHATTAN { 3 } else { 2 };
        let tail: String = if single {
            let value: &str = if result == RETURN_CELL_VALUE {
                "finish64(mul64(add64(vec2<u32>(a.x, h.x), cx), vec2<u32>(1619u, 0u)) ^ mul64(add64(vec2<u32>(a.y, h.y), cy), vec2<u32>(31337u, 0u)) ^ mul64(add64(vec2<u32>(a.z, h.z), cz), vec2<u32>(6971u, 0u)))"
            } else {
                "d1 - 1.0"
            };
            let gap: String = if result == RETURN_CELL_VALUE {
                "    if (d2 - d1 < 20.0 * eps) {\n        ambiguous = true;\n    }\n".to_string()
            } else {
                String::new()
            };
            format!(
                "    if (abs(tx - rxf) > 0.5 - 2.0 * eps || abs(ty - ryf) > 0.5 - 2.0 * eps || abs(tz - rzf) > 0.5 - 2.0 * eps) {{\n        ambiguous = true;\n    }}\n{gap}    return {value};\n"
            )
        } else {
            format!(
                r#"    let boundary: f32 = 1.55 - max(abs(tx - rxf), max(abs(ty - ryf), abs(tz - rzf)));
    if (measure_{distance}(boundary, 0.0, 0.0) < d2) {{
        for (var dx: i32 = -{radius}; dx <= {radius}; dx++) {{
            let xi: i32 = xr + dx;
            let bx: f32 = f32(xi) - tx;
            let ex: f32 = max(0.0, abs(bx) - 0.45);
            if (measure_{distance}(ex, 0.0, 0.0) >= d2) {{
                continue;
            }}
            for (var dy: i32 = -{radius}; dy <= {radius}; dy++) {{
                let yi: i32 = yr + dy;
                let by: f32 = f32(yi) - ty;
                let ey: f32 = max(0.0, abs(by) - 0.45);
                if (measure_{distance}(ex, ey, 0.0) >= d2) {{
                    continue;
                }}
                for (var dz: i32 = -{radius}; dz <= {radius}; dz++) {{
                    if (abs(dx) < 2 && abs(dy) < 2 && abs(dz) < 2) {{
                        continue;
                    }}
                    let zi: i32 = zr + dz;
                    let bz: f32 = f32(zi) - tz;
                    let ez: f32 = max(0.0, abs(bz) - 0.45);
                    if (measure_{distance}(ex, ey, ez) >= d2) {{
                        continue;
                    }}
                    let c: vec4<f32> = cells[256u + (hash3(s, a.x + bitcast<u32>(xi), a.y + bitcast<u32>(yi), a.z + bitcast<u32>(zi)) & 255u)];
                    let cand: f32 = measure_{distance}(bx + c.x, by + c.y, bz + c.z);
                    if (cand < d1) {{
                        d2 = d1;
                        d1 = cand;
                    }} else if (cand < d2) {{
                        d2 = cand;
                    }}
                }}
            }}
        }}
    }}
    return {};
"#,
                edge_expression(result)
            )
        };
        format!(
            r#"
fn cell3_{distance}_{result}(s: u32, r: u32, lx: f32, ly: f32, lz: f32, slack: f32) -> f32 {{
    let eps: f32 = slack + 1.1920929e-7 * (abs(lx) + abs(ly) + abs(lz));
    let a: vec4<u32> = rec[r * 3u];
    let b: vec4<u32> = rec[r * 3u + 1u];
    let h: vec4<u32> = rec[r * 3u + 2u];
    let tx: f32 = bitcast<f32>(b.x) + lx;
    let ty: f32 = bitcast<f32>(b.y) + ly;
    let tz: f32 = bitcast<f32>(b.z) + lz;
    let rxf: f32 = floor(tx + 0.5);
    let ryf: f32 = floor(ty + 0.5);
    let rzf: f32 = floor(tz + 0.5);
    let xr: i32 = i32(rxf);
    let yr: i32 = i32(ryf);
    let zr: i32 = i32(rzf);
    var d1: f32 = 999999.0;
    var d2: f32 = 999999.0;
    var cx: i32 = 0;
    var cy: i32 = 0;
    var cz: i32 = 0;
    for (var dx: i32 = -1; dx <= 1; dx++) {{
        let xi: i32 = xr + dx;
        let bx: f32 = f32(xi) - tx;
        for (var dy: i32 = -1; dy <= 1; dy++) {{
            let yi: i32 = yr + dy;
            let by: f32 = f32(yi) - ty;
            for (var dz: i32 = -1; dz <= 1; dz++) {{
                let zi: i32 = zr + dz;
                let bz: f32 = f32(zi) - tz;
                let c: vec4<f32> = cells[256u + (hash3(s, a.x + bitcast<u32>(xi), a.y + bitcast<u32>(yi), a.z + bitcast<u32>(zi)) & 255u)];
                let cand: f32 = measure_{distance}(bx + c.x, by + c.y, bz + c.z);
                let closer: bool = cand < d1;
                d2 = select(select(d2, cand, cand < d2), d1, closer);
                d1 = select(d1, cand, closer);
                cx = select(cx, xi, closer);
                cy = select(cy, yi, closer);
                cz = select(cz, zi, closer);
            }}
        }}
    }}
{tail}}}
"#
        )
    }
}
