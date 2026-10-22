use crate::kernels::{simplex2, simplex3};

pub fn billow_point(mut seed: i64, mut x: f64, mut y: f64, mut z: f64, dimensions: i32, octaves: i32, bounding: f64) -> f64 {
    x *= 0.01;
    y *= 0.01;
    z *= 0.01;
    let mut sum: f64 = sample(seed, x, y, z, dimensions).abs() * 2.0 - 1.0;
    let mut amplitude: f64 = 1.0;
    for _ in 1..octaves {
        x *= 2.0;
        y *= 2.0;
        z *= 2.0;
        amplitude *= 0.5;
        seed = seed.wrapping_add(1);
        sum += (sample(seed, x, y, z, dimensions).abs() * 2.0 - 1.0) * amplitude;
    }
    (sum * bounding) / 2.0 + 0.5
}

#[inline(always)]
fn sample(seed: i64, x: f64, y: f64, z: f64, dimensions: i32) -> f64 {
    if dimensions == 2 { simplex2(seed, x, z) } else { simplex3(seed, x, y, z) }
}

#[unsafe(no_mangle)]
pub extern "C" fn volmlib_billow2d_v1(seed: i64, x: f64, z: f64, octaves: i32, bounding: f64) -> f64 {
    billow_point(seed, x, 0.0, z, 2, octaves, bounding)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn billow_batch(seed: i64, xyz: *const f64, out: *mut f64, count: i32, dimensions: i32, octaves: i32, bounding: f64) {
    if xyz.is_null() || out.is_null() || count <= 0 {
        return;
    }
    let count: usize = count as usize;
    let input: &[f64] = unsafe { std::slice::from_raw_parts(xyz, count * 3) };
    let output: &mut [f64] = unsafe { std::slice::from_raw_parts_mut(out, count) };
    for (index, target) in output.iter_mut().enumerate() {
        *target = billow_point(seed, input[index * 3], input[index * 3 + 1], input[index * 3 + 2], dimensions, octaves, bounding);
    }
}
