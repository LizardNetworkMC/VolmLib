use crate::kernels::{self, Cellular, LONGERP_HERMITE, LONGERP_LINEAR, LONGERP_QULONGIC};
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

pub fn cellular2(cellular: &Cellular, xs: &[f64], ys: &[f64], out: &mut [f64]) {
    let length: usize = out.len();
    let xs: &[f64] = &xs[..length];
    let ys: &[f64] = &ys[..length];
    let mut start: usize = 0;
    #[cfg(target_arch = "aarch64")]
    {
        while start + 4 <= length {
            match neon::cellular2(cellular, neon::load(xs, start), neon::load(ys, start)) {
                Some(values) => neon::store(out, start, values),
                None => {
                    for index in start..start + 4 {
                        out[index] = cellular.sample2(xs[index], ys[index]);
                    }
                }
            }
            start += 4;
        }
    }
    for index in start..length {
        out[index] = cellular.sample2(xs[index], ys[index]);
    }
}

pub fn cellular3(cellular: &Cellular, xs: &[f64], ys: &[f64], zs: &[f64], out: &mut [f64]) {
    let length: usize = out.len();
    let xs: &[f64] = &xs[..length];
    let ys: &[f64] = &ys[..length];
    let zs: &[f64] = &zs[..length];
    let mut start: usize = 0;
    #[cfg(target_arch = "aarch64")]
    {
        while start + 4 <= length {
            match neon::cellular3(cellular, neon::load(xs, start), neon::load(ys, start), neon::load(zs, start)) {
                Some(values) => neon::store(out, start, values),
                None => {
                    for index in start..start + 4 {
                        out[index] = cellular.sample3(xs[index], ys[index], zs[index]);
                    }
                }
            }
            start += 4;
        }
    }
    for index in start..length {
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

    const LENGTH: usize = 1027;

    struct Coordinates {
        state: u64,
    }

    impl Coordinates {
        fn next(&mut self) -> u64 {
            self.state ^= self.state << 13;
            self.state ^= self.state >> 7;
            self.state ^= self.state << 17;
            self.state
        }

        fn coordinate(&mut self) -> f64 {
            let bits: u64 = self.next();
            let unit: f64 = (bits >> 11) as f64 / (1u64 << 53) as f64;
            match bits % 5 {
                0 => unit * 80.0 - 40.0,
                1 => ((bits >> 20) % 600_000) as f64 - 300_000.0,
                2 => unit * 600_000.0 - 300_000.0,
                3 => [0.0, -0.0, 0.5, -0.5, 1.0e-9, -299_999.995, 16.0][(bits >> 32) as usize % 7],
                _ => (unit * 64.0).floor() * 0.25 - 8.0,
            }
        }

        fn fill(&mut self, count: usize) -> Vec<f64> {
            (0..count).map(|_| self.coordinate()).collect()
        }
    }

    fn assert_bits(name: &str, expected: &[f64], actual: &[f64]) {
        for (index, (left, right)) in expected.iter().zip(actual).enumerate() {
            assert_eq!(left.to_bits(), right.to_bits(), "{name} differs at {index}: {left} vs {right}");
        }
    }

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
