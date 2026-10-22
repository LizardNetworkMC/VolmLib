use crate::kernels::Cellular;

pub fn simplex2(_seed: i64, _xs: &[f64], _ys: &[f64], _out: &mut [f64]) -> usize {
    0
}

pub fn simplex3(_seed: i64, _xs: &[f64], _ys: &[f64], _zs: &[f64], _out: &mut [f64]) -> usize {
    0
}

pub fn perlin2<const MODE: i32>(_seed: i64, _xs: &[f64], _ys: &[f64], _out: &mut [f64]) -> usize {
    0
}

pub fn perlin3<const MODE: i32>(_seed: i64, _xs: &[f64], _ys: &[f64], _zs: &[f64], _out: &mut [f64]) -> usize {
    0
}

pub fn cellular2(_cellular: &Cellular, _xs: &[f64], _ys: &[f64], _out: &mut [f64]) -> usize {
    0
}

pub fn cellular3(_cellular: &Cellular, _xs: &[f64], _ys: &[f64], _zs: &[f64], _out: &mut [f64]) -> usize {
    0
}
