use crate::kernels::{self, LONGERP_HERMITE, LONGERP_LINEAR, LONGERP_QULONGIC};
#[cfg(target_arch = "aarch64")]
use crate::neon;

pub fn simplex2(seed: i64, xs: &[f64], ys: &[f64], out: &mut [f64]) {
    let length: usize = out.len();
    let xs: &[f64] = &xs[..length];
    let ys: &[f64] = &ys[..length];
    let mut start: usize = 0;
    #[cfg(target_arch = "aarch64")]
    {
        while start + 4 <= length {
            neon::store(out, start, neon::simplex2(seed, neon::load(xs, start), neon::load(ys, start)));
            start += 4;
        }
    }
    for index in start..length {
        out[index] = kernels::simplex2(seed, xs[index], ys[index]);
    }
}

pub fn simplex3(seed: i64, xs: &[f64], ys: &[f64], zs: &[f64], out: &mut [f64]) {
    let length: usize = out.len();
    let xs: &[f64] = &xs[..length];
    let ys: &[f64] = &ys[..length];
    let zs: &[f64] = &zs[..length];
    let mut start: usize = 0;
    #[cfg(target_arch = "aarch64")]
    {
        while start + 4 <= length {
            neon::store(out, start, neon::simplex3(seed, neon::load(xs, start), neon::load(ys, start), neon::load(zs, start)));
            start += 4;
        }
    }
    for index in start..length {
        out[index] = kernels::simplex3(seed, xs[index], ys[index], zs[index]);
    }
}

pub fn perlin2(seed: i64, longerp: i32, xs: &[f64], ys: &[f64], out: &mut [f64]) {
    match longerp {
        LONGERP_QULONGIC => perlin2_mode::<LONGERP_QULONGIC>(seed, xs, ys, out),
        LONGERP_HERMITE => perlin2_mode::<LONGERP_HERMITE>(seed, xs, ys, out),
        _ => perlin2_mode::<LONGERP_LINEAR>(seed, xs, ys, out),
    }
}

pub fn perlin3(seed: i64, longerp: i32, xs: &[f64], ys: &[f64], zs: &[f64], out: &mut [f64]) {
    match longerp {
        LONGERP_QULONGIC => perlin3_mode::<LONGERP_QULONGIC>(seed, xs, ys, zs, out),
        LONGERP_HERMITE => perlin3_mode::<LONGERP_HERMITE>(seed, xs, ys, zs, out),
        _ => perlin3_mode::<LONGERP_LINEAR>(seed, xs, ys, zs, out),
    }
}

fn perlin2_mode<const MODE: i32>(seed: i64, xs: &[f64], ys: &[f64], out: &mut [f64]) {
    let length: usize = out.len();
    let xs: &[f64] = &xs[..length];
    let ys: &[f64] = &ys[..length];
    let mut start: usize = 0;
    #[cfg(target_arch = "aarch64")]
    {
        while start + 4 <= length {
            neon::store(out, start, neon::perlin2::<MODE>(seed, neon::load(xs, start), neon::load(ys, start)));
            start += 4;
        }
    }
    for index in start..length {
        out[index] = kernels::perlin2::<MODE>(seed, xs[index], ys[index]);
    }
}

fn perlin3_mode<const MODE: i32>(seed: i64, xs: &[f64], ys: &[f64], zs: &[f64], out: &mut [f64]) {
    let length: usize = out.len();
    let xs: &[f64] = &xs[..length];
    let ys: &[f64] = &ys[..length];
    let zs: &[f64] = &zs[..length];
    let mut start: usize = 0;
    #[cfg(target_arch = "aarch64")]
    {
        while start + 4 <= length {
            neon::store(
                out,
                start,
                neon::perlin3::<MODE>(seed, neon::load(xs, start), neon::load(ys, start), neon::load(zs, start)),
            );
            start += 4;
        }
    }
    for index in start..length {
        out[index] = kernels::perlin3::<MODE>(seed, xs[index], ys[index], zs[index]);
    }
}
