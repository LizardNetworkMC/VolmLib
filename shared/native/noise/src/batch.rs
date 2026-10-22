use crate::kernels::{self, Cellular, LONGERP_HERMITE, LONGERP_LINEAR, LONGERP_QULONGIC};
#[cfg(target_arch = "aarch64")]
use crate::neon as vector;
#[cfg(not(any(target_arch = "aarch64", target_arch = "x86_64")))]
use crate::portable as vector;
#[cfg(target_arch = "x86_64")]
use crate::x86 as vector;

pub fn simplex2(seed: i64, xs: &[f64], ys: &[f64], out: &mut [f64]) {
    let length: usize = out.len();
    let xs: &[f64] = &xs[..length];
    let ys: &[f64] = &ys[..length];
    for index in vector::simplex2(seed, xs, ys, out)..length {
        out[index] = kernels::simplex2(seed, xs[index], ys[index]);
    }
}

pub fn simplex3(seed: i64, xs: &[f64], ys: &[f64], zs: &[f64], out: &mut [f64]) {
    let length: usize = out.len();
    let xs: &[f64] = &xs[..length];
    let ys: &[f64] = &ys[..length];
    let zs: &[f64] = &zs[..length];
    for index in vector::simplex3(seed, xs, ys, zs, out)..length {
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
    for index in vector::perlin2::<MODE>(seed, xs, ys, out)..length {
        out[index] = kernels::perlin2::<MODE>(seed, xs[index], ys[index]);
    }
}

fn perlin3_mode<const MODE: i32>(seed: i64, xs: &[f64], ys: &[f64], zs: &[f64], out: &mut [f64]) {
    let length: usize = out.len();
    let xs: &[f64] = &xs[..length];
    let ys: &[f64] = &ys[..length];
    let zs: &[f64] = &zs[..length];
    for index in vector::perlin3::<MODE>(seed, xs, ys, zs, out)..length {
        out[index] = kernels::perlin3::<MODE>(seed, xs[index], ys[index], zs[index]);
    }
}

pub fn cellular2(cellular: &Cellular, xs: &[f64], ys: &[f64], out: &mut [f64]) {
    let length: usize = out.len();
    let xs: &[f64] = &xs[..length];
    let ys: &[f64] = &ys[..length];
    for index in vector::cellular2(cellular, xs, ys, out)..length {
        out[index] = cellular.sample2(xs[index], ys[index]);
    }
}

pub fn cellular3(cellular: &Cellular, xs: &[f64], ys: &[f64], zs: &[f64], out: &mut [f64]) {
    let length: usize = out.len();
    let xs: &[f64] = &xs[..length];
    let ys: &[f64] = &ys[..length];
    let zs: &[f64] = &zs[..length];
    for index in vector::cellular3(cellular, xs, ys, zs, out)..length {
        out[index] = cellular.sample3(xs[index], ys[index], zs[index]);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::kernels::{
        DISTANCE_EUCLIDEAN, DISTANCE_MANHATTAN, DISTANCE_NATURAL, RETURN_CELL_VALUE, RETURN_DISTANCE, RETURN_DISTANCE2,
        RETURN_DISTANCE2_ADD, RETURN_DISTANCE2_DIV, RETURN_DISTANCE2_MUL, RETURN_DISTANCE2_SUB,
    };
    use crate::testing::{assert_bits, Coordinates};

    const LENGTH: usize = 1027;

    #[test]
    fn vector_gradient_kernels_match_scalar_kernels() {
        let mut random: Coordinates = Coordinates { state: 0x9e37_79b9_7f4a_7c15 };
        for round in 0..24 {
            let seed: i64 = random.next() as i64;
            let xs: Vec<f64> = random.fill(LENGTH);
            let ys: Vec<f64> = random.fill(LENGTH);
            let zs: Vec<f64> = if round % 3 == 0 { xs.clone() } else { random.fill(LENGTH) };
            let mut actual: Vec<f64> = vec![0.0; LENGTH];
            simplex2(seed, &xs, &ys, &mut actual);
            let expected: Vec<f64> = (0..LENGTH).map(|index| kernels::simplex2(seed, xs[index], ys[index])).collect();
            assert_bits("simplex2", &expected, &actual);
            simplex3(seed, &xs, &ys, &zs, &mut actual);
            let expected: Vec<f64> = (0..LENGTH).map(|index| kernels::simplex3(seed, xs[index], ys[index], zs[index])).collect();
            assert_bits("simplex3", &expected, &actual);
            perlin2(seed, LONGERP_QULONGIC, &xs, &ys, &mut actual);
            let expected: Vec<f64> = (0..LENGTH).map(|index| kernels::perlin2::<LONGERP_QULONGIC>(seed, xs[index], ys[index])).collect();
            assert_bits("perlin2 quintic", &expected, &actual);
            perlin2(seed, LONGERP_HERMITE, &xs, &ys, &mut actual);
            let expected: Vec<f64> = (0..LENGTH).map(|index| kernels::perlin2::<LONGERP_HERMITE>(seed, xs[index], ys[index])).collect();
            assert_bits("perlin2 hermite", &expected, &actual);
            perlin2(seed, LONGERP_LINEAR, &xs, &ys, &mut actual);
            let expected: Vec<f64> = (0..LENGTH).map(|index| kernels::perlin2::<LONGERP_LINEAR>(seed, xs[index], ys[index])).collect();
            assert_bits("perlin2 linear", &expected, &actual);
            perlin3(seed, LONGERP_QULONGIC, &xs, &ys, &zs, &mut actual);
            let expected: Vec<f64> =
                (0..LENGTH).map(|index| kernels::perlin3::<LONGERP_QULONGIC>(seed, xs[index], ys[index], zs[index])).collect();
            assert_bits("perlin3 quintic", &expected, &actual);
            perlin3(seed, LONGERP_HERMITE, &xs, &ys, &zs, &mut actual);
            let expected: Vec<f64> =
                (0..LENGTH).map(|index| kernels::perlin3::<LONGERP_HERMITE>(seed, xs[index], ys[index], zs[index])).collect();
            assert_bits("perlin3 hermite", &expected, &actual);
        }
    }

    #[test]
    fn vector_cellular_kernels_match_scalar_kernels() {
        let mut random: Coordinates = Coordinates { state: 0x2545_f491_4f6c_dd1d };
        let distances: [i32; 3] = [DISTANCE_EUCLIDEAN, DISTANCE_MANHATTAN, DISTANCE_NATURAL];
        let results: [i32; 7] = [
            RETURN_CELL_VALUE,
            RETURN_DISTANCE,
            RETURN_DISTANCE2,
            RETURN_DISTANCE2_ADD,
            RETURN_DISTANCE2_SUB,
            RETURN_DISTANCE2_MUL,
            RETURN_DISTANCE2_DIV,
        ];
        for distance in distances {
            for result in results {
                let cellular: Cellular = Cellular { seed: random.next() as i64, distance, result };
                let xs: Vec<f64> = random.fill(LENGTH);
                let ys: Vec<f64> = random.fill(LENGTH);
                let zs: Vec<f64> = random.fill(LENGTH);
                let mut actual: Vec<f64> = vec![0.0; LENGTH];
                cellular2(&cellular, &xs, &ys, &mut actual);
                let expected: Vec<f64> = (0..LENGTH).map(|index| cellular.sample2(xs[index], ys[index])).collect();
                assert_bits("cellular2", &expected, &actual);
                cellular3(&cellular, &xs, &ys, &zs, &mut actual);
                let expected: Vec<f64> = (0..LENGTH).map(|index| cellular.sample3(xs[index], ys[index], zs[index])).collect();
                assert_bits("cellular3", &expected, &actual);
            }
        }
    }

    #[test]
    fn non_finite_lanes_use_the_scalar_cellular_kernel() {
        let cellular: Cellular = Cellular { seed: 1337, distance: DISTANCE_NATURAL, result: RETURN_DISTANCE2_SUB };
        let xs: Vec<f64> = vec![1.5, f64::NAN, -2.25, f64::INFINITY, 3.0, 4.0, 5.0, 6.0];
        let ys: Vec<f64> = vec![0.5, 1.0, f64::NEG_INFINITY, 2.0, 7.0, 8.0, 9.0, 10.0];
        let mut actual: Vec<f64> = vec![0.0; xs.len()];
        cellular2(&cellular, &xs, &ys, &mut actual);
        let expected: Vec<f64> = (0..xs.len()).map(|index| cellular.sample2(xs[index], ys[index])).collect();
        assert_bits("cellular2 non-finite", &expected, &actual);
    }
}
