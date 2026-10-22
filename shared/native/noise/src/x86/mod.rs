use std::sync::atomic::{AtomicU8, Ordering};

use crate::kernels::{
    self, val_coord2, Cellular, DISTANCE_EUCLIDEAN, DISTANCE_MANHATTAN, DISTANCE_NATURAL, F2, F3, G2, G3, G33, GRAD3_X_BYTES,
    GRAD3_Y_BYTES, GRAD3_Z_BYTES, LONGERP_HERMITE, LONGERP_QULONGIC, RETURN_CELL_VALUE, RETURN_DISTANCE, RETURN_DISTANCE2,
    RETURN_DISTANCE2_ADD, RETURN_DISTANCE2_DIV, RETURN_DISTANCE2_MUL, RETURN_DISTANCE2_SUB,
};
use crate::tables::{CELL_2D_X, CELL_2D_Y, CELL_3D_X, CELL_3D_Y, CELL_3D_Z};

mod avx2_lanes;
mod sse41_lanes;

use self::avx2_lanes::Avx2;
use self::sse41_lanes::Sse41;

const UNDETECTED: u8 = 0;
const SCALAR: u8 = 1;
const SSE41: u8 = 2;
const AVX2: u8 = 3;
const COORDINATE_LIMIT: f64 = (1u64 << 50) as f64;
const LOW_WORD_MAGIC: f64 = (3u64 << 51) as f64;
const NONE: u8 = 0x80;
const BIAS: [u8; 32] = bias(0);
const BIAS_LOW_TABLE: [u8; 32] = bias(0x70);
const BIAS_HIGH_TABLE: [u8; 32] = bias(0xf0);

static LEVEL: AtomicU8 = AtomicU8::new(UNDETECTED);

const fn bias(offset: u8) -> [u8; 32] {
    let mut table: [u8; 32] = [NONE; 32];
    let mut lane: usize = 0;
    while lane < 4 {
        table[lane * 8 + 6] = offset;
        table[lane * 8 + 7] = offset.wrapping_add(1);
        lane += 1;
    }
    table
}

const fn halves(table: &[u8; 32]) -> ([u8; 16], [u8; 16]) {
    let mut low: [u8; 16] = [0; 16];
    let mut high: [u8; 16] = [0; 16];
    let mut index: usize = 0;
    while index < 16 {
        low[index] = table[index];
        high[index] = table[index + 16];
        index += 1;
    }
    (low, high)
}

const GRAD3_X_HALVES: ([u8; 16], [u8; 16]) = halves(&GRAD3_X_BYTES);
const GRAD3_Y_HALVES: ([u8; 16], [u8; 16]) = halves(&GRAD3_Y_BYTES);
const GRAD3_Z_HALVES: ([u8; 16], [u8; 16]) = halves(&GRAD3_Z_BYTES);

fn level() -> u8 {
    let cached: u8 = LEVEL.load(Ordering::Relaxed);
    if cached != UNDETECTED {
        return cached;
    }
    let detected: u8 = if is_x86_feature_detected!("avx2") {
        AVX2
    } else if is_x86_feature_detected!("sse4.1") {
        SSE41
    } else {
        SCALAR
    };
    LEVEL.store(detected, Ordering::Relaxed);
    detected
}

trait Simd {
    type F: Copy;
    type M: Copy;
    type H: Copy;

    fn load(values: &[f64], offset: usize) -> Self::F;
    fn store(values: &mut [f64], offset: usize, value: Self::F);
    fn lanes(value: Self::F) -> [f64; 4];
    fn splat(value: f64) -> Self::F;
    fn add(left: Self::F, right: Self::F) -> Self::F;
    fn sub(left: Self::F, right: Self::F) -> Self::F;
    fn mul(left: Self::F, right: Self::F) -> Self::F;
    fn div(left: Self::F, right: Self::F) -> Self::F;
    fn absolute(value: Self::F) -> Self::F;
    fn floor(value: Self::F) -> Self::F;
    fn truncate(value: Self::F) -> Self::F;
    fn bounded(value: Self::F) -> bool;
    fn lt(left: Self::F, right: Self::F) -> Self::M;
    fn gt(left: Self::F, right: Self::F) -> Self::M;
    fn ge(left: Self::F, right: Self::F) -> Self::M;
    fn ordered(value: Self::F) -> Self::M;
    fn and(left: Self::M, right: Self::M) -> Self::M;
    fn or(left: Self::M, right: Self::M) -> Self::M;
    fn not(mask: Self::M) -> Self::M;
    fn choose(mask: Self::M, yes: Self::F, no: Self::F) -> Self::F;
    fn flag(mask: Self::M) -> Self::F;
    fn mask_lanes(mask: Self::M) -> [bool; 4];
    fn low_word(value: Self::F) -> Self::H;
    fn seed(seed: i64) -> Self::H;
    fn plus_one(value: Self::H) -> Self::H;
    fn plus_mask(value: Self::H, mask: Self::M) -> Self::H;
    fn hash2(seed: Self::H, x: Self::H, y: Self::H) -> Self::H;
    fn hash3(seed: Self::H, x: Self::H, y: Self::H, z: Self::H) -> Self::H;
    fn dot2(hash: Self::H, x: Self::F, y: Self::F) -> Self::F;
    fn dot3(hash: Self::H, x: Self::F, y: Self::F, z: Self::F) -> Self::F;
    fn gather(table: &[f64; 256], hash: Self::H) -> Self::F;
}

#[inline(always)]
fn corner<V: Simd>(t: V::F, gradient: V::F) -> V::F {
    let squared: V::F = V::mul(t, t);
    let value: V::F = V::mul(V::mul(squared, squared), gradient);
    V::choose(V::lt(t, V::splat(0.0)), V::splat(0.0), value)
}

#[inline(always)]
fn falloff2<V: Simd>(x: V::F, y: V::F) -> V::F {
    V::sub(V::sub(V::splat(0.5), V::mul(x, x)), V::mul(y, y))
}

#[inline(always)]
fn falloff3<V: Simd>(x: V::F, y: V::F, z: V::F) -> V::F {
    V::sub(V::sub(V::sub(V::splat(0.6), V::mul(x, x)), V::mul(y, y)), V::mul(z, z))
}

#[inline(always)]
fn simplex2_lanes<V: Simd>(seed: i64, x: V::F, y: V::F) -> V::F {
    let s: V::H = V::seed(seed);
    let g2: V::F = V::splat(G2);
    let skew: V::F = V::mul(V::add(x, y), V::splat(F2));
    let i: V::F = V::floor(V::add(x, skew));
    let j: V::F = V::floor(V::add(y, skew));
    let unskew: V::F = V::mul(V::add(i, j), g2);
    let x0: V::F = V::sub(x, V::sub(i, unskew));
    let y0: V::F = V::sub(y, V::sub(j, unskew));
    let first: V::M = V::gt(x0, y0);
    let x1: V::F = V::add(V::sub(x0, V::flag(first)), g2);
    let y1: V::F = V::add(V::sub(y0, V::flag(V::not(first))), g2);
    let g2x2: V::F = V::splat(2.0 * G2);
    let x2: V::F = V::add(V::sub(x0, V::splat(1.0)), g2x2);
    let y2: V::F = V::add(V::sub(y0, V::splat(1.0)), g2x2);
    let iu: V::H = V::low_word(i);
    let ju: V::H = V::low_word(j);
    let n0: V::F = corner::<V>(falloff2::<V>(x0, y0), V::dot2(V::hash2(s, iu, ju), x0, y0));
    let n1: V::F = corner::<V>(
        falloff2::<V>(x1, y1),
        V::dot2(V::hash2(s, V::plus_mask(iu, first), V::plus_mask(ju, V::not(first))), x1, y1),
    );
    let n2: V::F = corner::<V>(falloff2::<V>(x2, y2), V::dot2(V::hash2(s, V::plus_one(iu), V::plus_one(ju)), x2, y2));
    V::mul(V::splat(50.0), V::add(V::add(n0, n1), n2))
}

#[inline(always)]
fn simplex3_lanes<V: Simd>(seed: i64, x: V::F, y: V::F, z: V::F) -> V::F {
    let s: V::H = V::seed(seed);
    let skew: V::F = V::mul(V::add(V::add(x, y), z), V::splat(F3));
    let i: V::F = V::floor(V::add(x, skew));
    let j: V::F = V::floor(V::add(y, skew));
    let k: V::F = V::floor(V::add(z, skew));
    let unskew: V::F = V::mul(V::add(V::add(i, j), k), V::splat(G3));
    let x0: V::F = V::sub(x, V::sub(i, unskew));
    let y0: V::F = V::sub(y, V::sub(j, unskew));
    let z0: V::F = V::sub(z, V::sub(k, unskew));
    let a: V::M = V::ge(x0, y0);
    let b1: V::M = V::ge(y0, z0);
    let c1: V::M = V::ge(x0, z0);
    let b2: V::M = V::lt(y0, z0);
    let c2: V::M = V::lt(x0, z0);
    let i1: V::M = V::and(a, V::or(b1, c1));
    let j1: V::M = V::and(V::not(a), V::not(b2));
    let k1: V::M = V::or(V::and(a, V::and(V::not(b1), V::not(c1))), V::and(V::not(a), b2));
    let i2: V::M = V::or(a, V::and(V::not(b2), V::not(c2)));
    let j2: V::M = V::or(V::not(a), b1);
    let k2: V::M = V::or(V::and(a, V::not(b1)), V::and(V::not(a), V::or(b2, c2)));
    let g3: V::F = V::splat(G3);
    let f3: V::F = V::splat(F3);
    let x1: V::F = V::add(V::sub(x0, V::flag(i1)), g3);
    let y1: V::F = V::add(V::sub(y0, V::flag(j1)), g3);
    let z1: V::F = V::add(V::sub(z0, V::flag(k1)), g3);
    let x2: V::F = V::add(V::sub(x0, V::flag(i2)), f3);
    let y2: V::F = V::add(V::sub(y0, V::flag(j2)), f3);
    let z2: V::F = V::add(V::sub(z0, V::flag(k2)), f3);
    let g33: V::F = V::splat(G33);
    let x3: V::F = V::add(x0, g33);
    let y3: V::F = V::add(y0, g33);
    let z3: V::F = V::add(z0, g33);
    let iu: V::H = V::low_word(i);
    let ju: V::H = V::low_word(j);
    let ku: V::H = V::low_word(k);
    let n0: V::F = corner::<V>(falloff3::<V>(x0, y0, z0), V::dot3(V::hash3(s, iu, ju, ku), x0, y0, z0));
    let n1: V::F = corner::<V>(
        falloff3::<V>(x1, y1, z1),
        V::dot3(V::hash3(s, V::plus_mask(iu, i1), V::plus_mask(ju, j1), V::plus_mask(ku, k1)), x1, y1, z1),
    );
    let n2: V::F = corner::<V>(
        falloff3::<V>(x2, y2, z2),
        V::dot3(V::hash3(s, V::plus_mask(iu, i2), V::plus_mask(ju, j2), V::plus_mask(ku, k2)), x2, y2, z2),
    );
    let n3: V::F = corner::<V>(
        falloff3::<V>(x3, y3, z3),
        V::dot3(V::hash3(s, V::plus_one(iu), V::plus_one(ju), V::plus_one(ku)), x3, y3, z3),
    );
    V::mul(V::splat(32.0), V::add(V::add(V::add(n0, n1), n2), n3))
}

#[inline(always)]
fn lerp<V: Simd>(a: V::F, b: V::F, t: V::F) -> V::F {
    V::add(a, V::mul(t, V::sub(b, a)))
}

#[inline(always)]
fn interpolant<V: Simd, const MODE: i32>(t: V::F) -> V::F {
    match MODE {
        LONGERP_HERMITE => V::mul(V::mul(t, t), V::sub(V::splat(3.0), V::mul(V::splat(2.0), t))),
        LONGERP_QULONGIC => V::mul(
            V::mul(V::mul(t, t), t),
            V::add(V::mul(t, V::sub(V::mul(t, V::splat(6.0)), V::splat(15.0))), V::splat(10.0)),
        ),
        _ => t,
    }
}

#[inline(always)]
fn perlin2_lanes<V: Simd, const MODE: i32>(seed: i64, x: V::F, y: V::F) -> V::F {
    let s: V::H = V::seed(seed);
    let x0: V::F = V::floor(x);
    let y0: V::F = V::floor(y);
    let xd0: V::F = V::sub(x, x0);
    let yd0: V::F = V::sub(y, y0);
    let xs: V::F = interpolant::<V, MODE>(xd0);
    let ys: V::F = interpolant::<V, MODE>(yd0);
    let xd1: V::F = V::sub(xd0, V::splat(1.0));
    let yd1: V::F = V::sub(yd0, V::splat(1.0));
    let xu: V::H = V::low_word(x0);
    let yu: V::H = V::low_word(y0);
    let xv: V::H = V::plus_one(xu);
    let yv: V::H = V::plus_one(yu);
    let xf0: V::F = lerp::<V>(V::dot2(V::hash2(s, xu, yu), xd0, yd0), V::dot2(V::hash2(s, xv, yu), xd1, yd0), xs);
    let xf1: V::F = lerp::<V>(V::dot2(V::hash2(s, xu, yv), xd0, yd1), V::dot2(V::hash2(s, xv, yv), xd1, yd1), xs);
    lerp::<V>(xf0, xf1, ys)
}

#[inline(always)]
fn perlin3_lanes<V: Simd, const MODE: i32>(seed: i64, x: V::F, y: V::F, z: V::F) -> V::F {
    let s: V::H = V::seed(seed);
    let x0: V::F = V::floor(x);
    let y0: V::F = V::floor(y);
    let z0: V::F = V::floor(z);
    let xd0: V::F = V::sub(x, x0);
    let yd0: V::F = V::sub(y, y0);
    let zd0: V::F = V::sub(z, z0);
    let xs: V::F = interpolant::<V, MODE>(xd0);
    let ys: V::F = interpolant::<V, MODE>(yd0);
    let zs: V::F = interpolant::<V, MODE>(zd0);
    let xd1: V::F = V::sub(xd0, V::splat(1.0));
    let yd1: V::F = V::sub(yd0, V::splat(1.0));
    let zd1: V::F = V::sub(zd0, V::splat(1.0));
    let xu: V::H = V::low_word(x0);
    let yu: V::H = V::low_word(y0);
    let zu: V::H = V::low_word(z0);
    let xv: V::H = V::plus_one(xu);
    let yv: V::H = V::plus_one(yu);
    let zv: V::H = V::plus_one(zu);
    let xf00: V::F = lerp::<V>(V::dot3(V::hash3(s, xu, yu, zu), xd0, yd0, zd0), V::dot3(V::hash3(s, xv, yu, zu), xd1, yd0, zd0), xs);
    let xf10: V::F = lerp::<V>(V::dot3(V::hash3(s, xu, yv, zu), xd0, yd1, zd0), V::dot3(V::hash3(s, xv, yv, zu), xd1, yd1, zd0), xs);
    let xf01: V::F = lerp::<V>(V::dot3(V::hash3(s, xu, yu, zv), xd0, yd0, zd1), V::dot3(V::hash3(s, xv, yu, zv), xd1, yd0, zd1), xs);
    let xf11: V::F = lerp::<V>(V::dot3(V::hash3(s, xu, yv, zv), xd0, yd1, zd1), V::dot3(V::hash3(s, xv, yv, zv), xd1, yd1, zd1), xs);
    lerp::<V>(lerp::<V>(xf00, xf10, ys), lerp::<V>(xf01, xf11, ys), zs)
}

#[inline(always)]
fn round_cell<V: Simd>(value: V::F) -> V::F {
    V::truncate(V::choose(V::ge(value, V::splat(0.0)), V::add(value, V::splat(0.5)), V::sub(value, V::splat(0.5))))
}

#[inline(always)]
fn java_max<V: Simd>(a: V::F, b: V::F) -> V::F {
    V::choose(V::ordered(a), V::choose(V::ge(a, b), a, b), a)
}

#[inline(always)]
fn measure2<V: Simd, const DISTANCE: i32>(x: V::F, y: V::F) -> V::F {
    match DISTANCE {
        DISTANCE_EUCLIDEAN => V::add(V::mul(x, x), V::mul(y, y)),
        DISTANCE_MANHATTAN => V::add(V::absolute(x), V::absolute(y)),
        _ => V::add(V::add(V::absolute(x), V::absolute(y)), V::add(V::mul(x, x), V::mul(y, y))),
    }
}

#[inline(always)]
fn measure3<V: Simd, const DISTANCE: i32>(x: V::F, y: V::F, z: V::F) -> V::F {
    match DISTANCE {
        DISTANCE_EUCLIDEAN => V::add(V::add(V::mul(x, x), V::mul(y, y)), V::mul(z, z)),
        DISTANCE_MANHATTAN => V::add(V::add(V::absolute(x), V::absolute(y)), V::absolute(z)),
        _ => V::add(
            V::add(V::add(V::absolute(x), V::absolute(y)), V::absolute(z)),
            V::add(V::add(V::mul(x, x), V::mul(y, y)), V::mul(z, z)),
        ),
    }
}

#[inline(always)]
fn edge_result<V: Simd>(result: i32, distance: V::F, distance2: V::F) -> V::F {
    let one: V::F = V::splat(1.0);
    match result {
        RETURN_DISTANCE2 => V::sub(distance2, one),
        RETURN_DISTANCE2_ADD => V::sub(V::add(distance2, distance), one),
        RETURN_DISTANCE2_SUB => V::sub(V::sub(distance2, distance), one),
        RETURN_DISTANCE2_MUL => V::sub(V::mul(distance2, distance), one),
        RETURN_DISTANCE2_DIV => V::sub(V::div(distance, distance2), one),
        _ => V::splat(0.0),
    }
}

#[inline(always)]
fn cell_lanes<V: Simd>(value: V::F) -> [i64; 4] {
    let values: [f64; 4] = V::lanes(value);
    [values[0] as i64, values[1] as i64, values[2] as i64, values[3] as i64]
}

#[inline(always)]
fn cellular2_lanes<V: Simd, const DISTANCE: i32>(cellular: &Cellular, x: V::F, y: V::F) -> V::F {
    let seed: V::H = V::seed(cellular.seed);
    let xr: V::F = round_cell::<V>(x);
    let yr: V::F = round_cell::<V>(y);
    let single: bool = cellular.result == RETURN_CELL_VALUE || cellular.result == RETURN_DISTANCE;
    let mut distance: V::F = V::splat(999999.0);
    let mut distance2: V::F = V::splat(999999.0);
    let mut xc: V::F = V::splat(0.0);
    let mut yc: V::F = xc;
    for dx in -1i32..=1 {
        let xi: V::F = V::add(xr, V::splat(dx as f64));
        let base_x: V::F = V::sub(xi, x);
        let xu: V::H = V::low_word(xi);
        for dy in -1i32..=1 {
            let yi: V::F = V::add(yr, V::splat(dy as f64));
            let base_y: V::F = V::sub(yi, y);
            let hash: V::H = V::hash2(seed, xu, V::low_word(yi));
            let candidate: V::F =
                measure2::<V, DISTANCE>(V::add(base_x, V::gather(&CELL_2D_X, hash)), V::add(base_y, V::gather(&CELL_2D_Y, hash)));
            let closer: V::M = V::lt(candidate, distance);
            if single {
                xc = V::choose(closer, xi, xc);
                yc = V::choose(closer, yi, yc);
            } else {
                distance2 = V::choose(closer, distance, V::choose(V::lt(candidate, distance2), candidate, distance2));
            }
            distance = V::choose(closer, candidate, distance);
        }
    }
    if single {
        if cellular.result == RETURN_DISTANCE {
            return V::sub(distance, V::splat(1.0));
        }
        let xs: [i64; 4] = cell_lanes::<V>(xc);
        let ys: [i64; 4] = cell_lanes::<V>(yc);
        let values: [f64; 4] =
            [val_coord2(0, xs[0], ys[0]), val_coord2(0, xs[1], ys[1]), val_coord2(0, xs[2], ys[2]), val_coord2(0, xs[3], ys[3])];
        return V::load(&values, 0);
    }
    let zero: V::F = V::splat(0.0);
    let boundary: V::F =
        V::sub(V::splat(1.55), java_max::<V>(V::absolute(V::sub(x, xr)), V::absolute(V::sub(y, yr))));
    let refine: [bool; 4] = V::mask_lanes(V::lt(measure3::<V, DISTANCE>(boundary, zero, zero), distance2));
    let result: V::F = edge_result::<V>(cellular.result, distance, distance2);
    if !(refine[0] | refine[1] | refine[2] | refine[3]) {
        return result;
    }
    let mut values: [f64; 4] = V::lanes(result);
    let xs: [f64; 4] = V::lanes(x);
    let ys: [f64; 4] = V::lanes(y);
    let xrs: [i64; 4] = cell_lanes::<V>(xr);
    let yrs: [i64; 4] = cell_lanes::<V>(yr);
    let distances: [f64; 4] = V::lanes(distance);
    let distances2: [f64; 4] = V::lanes(distance2);
    for lane in 0..4 {
        if refine[lane] {
            values[lane] = cellular.refine2(xs[lane], ys[lane], xrs[lane], yrs[lane], distances[lane], distances2[lane]);
        }
    }
    V::load(&values, 0)
}

#[inline(always)]
fn cellular3_lanes<V: Simd, const DISTANCE: i32>(cellular: &Cellular, x: V::F, y: V::F, z: V::F) -> V::F {
    let seed: V::H = V::seed(cellular.seed);
    let xr: V::F = round_cell::<V>(x);
    let yr: V::F = round_cell::<V>(y);
    let zr: V::F = round_cell::<V>(z);
    let mut distance: V::F = V::splat(999999.0);
    let mut distance2: V::F = V::splat(999999.0);
    for dx in -1i32..=1 {
        let xi: V::F = V::add(xr, V::splat(dx as f64));
        let base_x: V::F = V::sub(xi, x);
        let xu: V::H = V::low_word(xi);
        for dy in -1i32..=1 {
            let yi: V::F = V::add(yr, V::splat(dy as f64));
            let base_y: V::F = V::sub(yi, y);
            let yu: V::H = V::low_word(yi);
            for dz in -1i32..=1 {
                let zi: V::F = V::add(zr, V::splat(dz as f64));
                let base_z: V::F = V::sub(zi, z);
                let hash: V::H = V::hash3(seed, xu, yu, V::low_word(zi));
                let candidate: V::F = measure3::<V, DISTANCE>(
                    V::add(base_x, V::gather(&CELL_3D_X, hash)),
                    V::add(base_y, V::gather(&CELL_3D_Y, hash)),
                    V::add(base_z, V::gather(&CELL_3D_Z, hash)),
                );
                let closer: V::M = V::lt(candidate, distance);
                distance2 = V::choose(closer, distance, V::choose(V::lt(candidate, distance2), candidate, distance2));
                distance = V::choose(closer, candidate, distance);
            }
        }
    }
    let zero: V::F = V::splat(0.0);
    let boundary: V::F = V::sub(
        V::splat(1.55),
        java_max::<V>(V::absolute(V::sub(x, xr)), java_max::<V>(V::absolute(V::sub(y, yr)), V::absolute(V::sub(z, zr)))),
    );
    let refine: [bool; 4] = V::mask_lanes(V::lt(measure3::<V, DISTANCE>(boundary, zero, zero), distance2));
    let result: V::F = edge_result::<V>(cellular.result, distance, distance2);
    if !(refine[0] | refine[1] | refine[2] | refine[3]) {
        return result;
    }
    let mut values: [f64; 4] = V::lanes(result);
    let xs: [f64; 4] = V::lanes(x);
    let ys: [f64; 4] = V::lanes(y);
    let zs: [f64; 4] = V::lanes(z);
    let xrs: [i64; 4] = cell_lanes::<V>(xr);
    let yrs: [i64; 4] = cell_lanes::<V>(yr);
    let zrs: [i64; 4] = cell_lanes::<V>(zr);
    let distances: [f64; 4] = V::lanes(distance);
    let distances2: [f64; 4] = V::lanes(distance2);
    for lane in 0..4 {
        if refine[lane] {
            values[lane] =
                cellular.refine3(xs[lane], ys[lane], zs[lane], xrs[lane], yrs[lane], zrs[lane], distances[lane], distances2[lane]);
        }
    }
    V::load(&values, 0)
}

#[inline(always)]
fn simplex2_slices<V: Simd>(seed: i64, xs: &[f64], ys: &[f64], out: &mut [f64]) -> usize {
    let quads: usize = out.len() / 4 * 4;
    for start in (0..quads).step_by(4) {
        let x: V::F = V::load(xs, start);
        let y: V::F = V::load(ys, start);
        if V::bounded(x) & V::bounded(y) {
            V::store(out, start, simplex2_lanes::<V>(seed, x, y));
        } else {
            for index in start..start + 4 {
                out[index] = kernels::simplex2(seed, xs[index], ys[index]);
            }
        }
    }
    quads
}

#[inline(always)]
fn simplex3_slices<V: Simd>(seed: i64, xs: &[f64], ys: &[f64], zs: &[f64], out: &mut [f64]) -> usize {
    let quads: usize = out.len() / 4 * 4;
    for start in (0..quads).step_by(4) {
        let x: V::F = V::load(xs, start);
        let y: V::F = V::load(ys, start);
        let z: V::F = V::load(zs, start);
        if V::bounded(x) & V::bounded(y) & V::bounded(z) {
            V::store(out, start, simplex3_lanes::<V>(seed, x, y, z));
        } else {
            for index in start..start + 4 {
                out[index] = kernels::simplex3(seed, xs[index], ys[index], zs[index]);
            }
        }
    }
    quads
}

#[inline(always)]
fn perlin2_slices<V: Simd, const MODE: i32>(seed: i64, xs: &[f64], ys: &[f64], out: &mut [f64]) -> usize {
    let quads: usize = out.len() / 4 * 4;
    for start in (0..quads).step_by(4) {
        let x: V::F = V::load(xs, start);
        let y: V::F = V::load(ys, start);
        if V::bounded(x) & V::bounded(y) {
            V::store(out, start, perlin2_lanes::<V, MODE>(seed, x, y));
        } else {
            for index in start..start + 4 {
                out[index] = kernels::perlin2::<MODE>(seed, xs[index], ys[index]);
            }
        }
    }
    quads
}

#[inline(always)]
fn perlin3_slices<V: Simd, const MODE: i32>(seed: i64, xs: &[f64], ys: &[f64], zs: &[f64], out: &mut [f64]) -> usize {
    let quads: usize = out.len() / 4 * 4;
    for start in (0..quads).step_by(4) {
        let x: V::F = V::load(xs, start);
        let y: V::F = V::load(ys, start);
        let z: V::F = V::load(zs, start);
        if V::bounded(x) & V::bounded(y) & V::bounded(z) {
            V::store(out, start, perlin3_lanes::<V, MODE>(seed, x, y, z));
        } else {
            for index in start..start + 4 {
                out[index] = kernels::perlin3::<MODE>(seed, xs[index], ys[index], zs[index]);
            }
        }
    }
    quads
}

#[inline(always)]
fn cellular2_distance<V: Simd, const DISTANCE: i32>(cellular: &Cellular, xs: &[f64], ys: &[f64], out: &mut [f64]) -> usize {
    let quads: usize = out.len() / 4 * 4;
    for start in (0..quads).step_by(4) {
        let x: V::F = V::load(xs, start);
        let y: V::F = V::load(ys, start);
        if V::bounded(x) & V::bounded(y) {
            V::store(out, start, cellular2_lanes::<V, DISTANCE>(cellular, x, y));
        } else {
            for index in start..start + 4 {
                out[index] = cellular.sample2(xs[index], ys[index]);
            }
        }
    }
    quads
}

#[inline(always)]
fn cellular3_distance<V: Simd, const DISTANCE: i32>(
    cellular: &Cellular,
    xs: &[f64],
    ys: &[f64],
    zs: &[f64],
    out: &mut [f64],
) -> usize {
    let quads: usize = out.len() / 4 * 4;
    for start in (0..quads).step_by(4) {
        let x: V::F = V::load(xs, start);
        let y: V::F = V::load(ys, start);
        let z: V::F = V::load(zs, start);
        if V::bounded(x) & V::bounded(y) & V::bounded(z) {
            V::store(out, start, cellular3_lanes::<V, DISTANCE>(cellular, x, y, z));
        } else {
            for index in start..start + 4 {
                out[index] = cellular.sample3(xs[index], ys[index], zs[index]);
            }
        }
    }
    quads
}

#[inline(always)]
fn cellular2_slices<V: Simd>(cellular: &Cellular, xs: &[f64], ys: &[f64], out: &mut [f64]) -> usize {
    match cellular.distance {
        DISTANCE_EUCLIDEAN => cellular2_distance::<V, DISTANCE_EUCLIDEAN>(cellular, xs, ys, out),
        DISTANCE_MANHATTAN => cellular2_distance::<V, DISTANCE_MANHATTAN>(cellular, xs, ys, out),
        _ => cellular2_distance::<V, DISTANCE_NATURAL>(cellular, xs, ys, out),
    }
}

#[inline(always)]
fn cellular3_slices<V: Simd>(cellular: &Cellular, xs: &[f64], ys: &[f64], zs: &[f64], out: &mut [f64]) -> usize {
    if cellular.result == RETURN_CELL_VALUE || cellular.result == RETURN_DISTANCE {
        return 0;
    }
    match cellular.distance {
        DISTANCE_EUCLIDEAN => cellular3_distance::<V, DISTANCE_EUCLIDEAN>(cellular, xs, ys, zs, out),
        DISTANCE_MANHATTAN => cellular3_distance::<V, DISTANCE_MANHATTAN>(cellular, xs, ys, zs, out),
        _ => cellular3_distance::<V, DISTANCE_NATURAL>(cellular, xs, ys, zs, out),
    }
}

macro_rules! entries {
    ($module:ident, $simd:ty, $feature:literal) => {
        mod $module {
            use super::*;

            #[target_feature(enable = $feature)]
            pub fn simplex2(seed: i64, xs: &[f64], ys: &[f64], out: &mut [f64]) -> usize {
                simplex2_slices::<$simd>(seed, xs, ys, out)
            }

            #[target_feature(enable = $feature)]
            pub fn simplex3(seed: i64, xs: &[f64], ys: &[f64], zs: &[f64], out: &mut [f64]) -> usize {
                simplex3_slices::<$simd>(seed, xs, ys, zs, out)
            }

            #[target_feature(enable = $feature)]
            pub fn perlin2<const MODE: i32>(seed: i64, xs: &[f64], ys: &[f64], out: &mut [f64]) -> usize {
                perlin2_slices::<$simd, MODE>(seed, xs, ys, out)
            }

            #[target_feature(enable = $feature)]
            pub fn perlin3<const MODE: i32>(seed: i64, xs: &[f64], ys: &[f64], zs: &[f64], out: &mut [f64]) -> usize {
                perlin3_slices::<$simd, MODE>(seed, xs, ys, zs, out)
            }

            #[target_feature(enable = $feature)]
            pub fn cellular2(cellular: &Cellular, xs: &[f64], ys: &[f64], out: &mut [f64]) -> usize {
                cellular2_slices::<$simd>(cellular, xs, ys, out)
            }

            #[target_feature(enable = $feature)]
            pub fn cellular3(cellular: &Cellular, xs: &[f64], ys: &[f64], zs: &[f64], out: &mut [f64]) -> usize {
                cellular3_slices::<$simd>(cellular, xs, ys, zs, out)
            }
        }
    };
}

entries!(avx2, Avx2, "avx2");
entries!(sse41, Sse41, "sse4.1");

pub fn simplex2(seed: i64, xs: &[f64], ys: &[f64], out: &mut [f64]) -> usize {
    match level() {
        AVX2 => unsafe { avx2::simplex2(seed, xs, ys, out) },
        SSE41 => unsafe { sse41::simplex2(seed, xs, ys, out) },
        _ => 0,
    }
}

pub fn simplex3(seed: i64, xs: &[f64], ys: &[f64], zs: &[f64], out: &mut [f64]) -> usize {
    match level() {
        AVX2 => unsafe { avx2::simplex3(seed, xs, ys, zs, out) },
        SSE41 => unsafe { sse41::simplex3(seed, xs, ys, zs, out) },
        _ => 0,
    }
}

pub fn perlin2<const MODE: i32>(seed: i64, xs: &[f64], ys: &[f64], out: &mut [f64]) -> usize {
    match level() {
        AVX2 => unsafe { avx2::perlin2::<MODE>(seed, xs, ys, out) },
        SSE41 => unsafe { sse41::perlin2::<MODE>(seed, xs, ys, out) },
        _ => 0,
    }
}

pub fn perlin3<const MODE: i32>(seed: i64, xs: &[f64], ys: &[f64], zs: &[f64], out: &mut [f64]) -> usize {
    match level() {
        AVX2 => unsafe { avx2::perlin3::<MODE>(seed, xs, ys, zs, out) },
        SSE41 => unsafe { sse41::perlin3::<MODE>(seed, xs, ys, zs, out) },
        _ => 0,
    }
}

pub fn cellular2(cellular: &Cellular, xs: &[f64], ys: &[f64], out: &mut [f64]) -> usize {
    match level() {
        AVX2 => unsafe { avx2::cellular2(cellular, xs, ys, out) },
        SSE41 => unsafe { sse41::cellular2(cellular, xs, ys, out) },
        _ => 0,
    }
}

pub fn cellular3(cellular: &Cellular, xs: &[f64], ys: &[f64], zs: &[f64], out: &mut [f64]) -> usize {
    match level() {
        AVX2 => unsafe { avx2::cellular3(cellular, xs, ys, zs, out) },
        SSE41 => unsafe { sse41::cellular3(cellular, xs, ys, zs, out) },
        _ => 0,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::kernels::LONGERP_LINEAR;
    use crate::testing::{assert_bits, Coordinates};

    const LENGTH: usize = 1027;

    type Gradient2 = unsafe fn(i64, &[f64], &[f64], &mut [f64]) -> usize;
    type Gradient3 = unsafe fn(i64, &[f64], &[f64], &[f64], &mut [f64]) -> usize;
    type Cellular2 = unsafe fn(&Cellular, &[f64], &[f64], &mut [f64]) -> usize;
    type Cellular3 = unsafe fn(&Cellular, &[f64], &[f64], &[f64], &mut [f64]) -> usize;

    struct Level {
        name: &'static str,
        simplex2: Gradient2,
        simplex3: Gradient3,
        perlin2: [Gradient2; 3],
        perlin3: [Gradient3; 3],
        cellular2: Cellular2,
        cellular3: Cellular3,
    }

    fn levels() -> Vec<Level> {
        let mut levels: Vec<Level> = Vec::new();
        if is_x86_feature_detected!("avx2") {
            levels.push(Level {
                name: "avx2",
                simplex2: avx2::simplex2,
                simplex3: avx2::simplex3,
                perlin2: [avx2::perlin2::<LONGERP_LINEAR>, avx2::perlin2::<LONGERP_HERMITE>, avx2::perlin2::<LONGERP_QULONGIC>],
                perlin3: [avx2::perlin3::<LONGERP_LINEAR>, avx2::perlin3::<LONGERP_HERMITE>, avx2::perlin3::<LONGERP_QULONGIC>],
                cellular2: avx2::cellular2,
                cellular3: avx2::cellular3,
            });
        }
        if is_x86_feature_detected!("sse4.1") {
            levels.push(Level {
                name: "sse4.1",
                simplex2: sse41::simplex2,
                simplex3: sse41::simplex3,
                perlin2: [sse41::perlin2::<LONGERP_LINEAR>, sse41::perlin2::<LONGERP_HERMITE>, sse41::perlin2::<LONGERP_QULONGIC>],
                perlin3: [sse41::perlin3::<LONGERP_LINEAR>, sse41::perlin3::<LONGERP_HERMITE>, sse41::perlin3::<LONGERP_QULONGIC>],
                cellular2: sse41::cellular2,
                cellular3: sse41::cellular3,
            });
        }
        levels
    }

    fn complete2(kernel: Gradient2, scalar: fn(i64, f64, f64) -> f64, seed: i64, xs: &[f64], ys: &[f64]) -> Vec<f64> {
        let mut out: Vec<f64> = vec![f64::from_bits(0x7ff4_dead_beef_0001); xs.len()];
        let done: usize = unsafe { kernel(seed, xs, ys, &mut out) };
        assert_eq!(done, xs.len() / 4 * 4);
        for index in done..xs.len() {
            out[index] = scalar(seed, xs[index], ys[index]);
        }
        out
    }

    fn complete3(kernel: Gradient3, scalar: fn(i64, f64, f64, f64) -> f64, seed: i64, xs: &[f64], ys: &[f64], zs: &[f64]) -> Vec<f64> {
        let mut out: Vec<f64> = vec![f64::from_bits(0x7ff4_dead_beef_0001); xs.len()];
        let done: usize = unsafe { kernel(seed, xs, ys, zs, &mut out) };
        assert_eq!(done, xs.len() / 4 * 4);
        for index in done..xs.len() {
            out[index] = scalar(seed, xs[index], ys[index], zs[index]);
        }
        out
    }

    #[test]
    fn detected_levels_cover_this_host() {
        let detected: u8 = level();
        assert!(detected == AVX2 || detected == SSE41 || detected == SCALAR);
        assert_eq!(detected == AVX2, is_x86_feature_detected!("avx2"));
    }

    #[test]
    fn x86_gradient_kernels_match_scalar_kernels_on_every_level() {
        let perlin2_scalar: [fn(i64, f64, f64) -> f64; 3] =
            [kernels::perlin2::<LONGERP_LINEAR>, kernels::perlin2::<LONGERP_HERMITE>, kernels::perlin2::<LONGERP_QULONGIC>];
        let perlin3_scalar: [fn(i64, f64, f64, f64) -> f64; 3] =
            [kernels::perlin3::<LONGERP_LINEAR>, kernels::perlin3::<LONGERP_HERMITE>, kernels::perlin3::<LONGERP_QULONGIC>];
        for level in levels() {
            let mut random: Coordinates = Coordinates { state: 0x9e37_79b9_7f4a_7c15 };
            for round in 0..48 {
                let seed: i64 = random.next() as i64;
                let xs: Vec<f64> = random.fill(LENGTH);
                let ys: Vec<f64> = random.fill(LENGTH);
                let zs: Vec<f64> = if round % 3 == 0 { xs.clone() } else { random.fill(LENGTH) };
                let expected: Vec<f64> = (0..LENGTH).map(|index| kernels::simplex2(seed, xs[index], ys[index])).collect();
                assert_bits(&format!("{} simplex2", level.name), &expected, &complete2(level.simplex2, kernels::simplex2, seed, &xs, &ys));
                let expected: Vec<f64> = (0..LENGTH).map(|index| kernels::simplex3(seed, xs[index], ys[index], zs[index])).collect();
                assert_bits(
                    &format!("{} simplex3", level.name),
                    &expected,
                    &complete3(level.simplex3, kernels::simplex3, seed, &xs, &ys, &zs),
                );
                for mode in 0..3 {
                    let expected: Vec<f64> = (0..LENGTH).map(|index| perlin2_scalar[mode](seed, xs[index], ys[index])).collect();
                    assert_bits(
                        &format!("{} perlin2 mode {mode}", level.name),
                        &expected,
                        &complete2(level.perlin2[mode], perlin2_scalar[mode], seed, &xs, &ys),
                    );
                    let expected: Vec<f64> =
                        (0..LENGTH).map(|index| perlin3_scalar[mode](seed, xs[index], ys[index], zs[index])).collect();
                    assert_bits(
                        &format!("{} perlin3 mode {mode}", level.name),
                        &expected,
                        &complete3(level.perlin3[mode], perlin3_scalar[mode], seed, &xs, &ys, &zs),
                    );
                }
            }
        }
    }

    #[test]
    fn x86_cellular_kernels_match_scalar_kernels_on_every_level() {
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
        for level in levels() {
            let mut random: Coordinates = Coordinates { state: 0x2545_f491_4f6c_dd1d };
            for distance in distances {
                for result in results {
                    let cellular: Cellular = Cellular { seed: random.next() as i64, distance, result };
                    let xs: Vec<f64> = random.fill(LENGTH);
                    let ys: Vec<f64> = random.fill(LENGTH);
                    let zs: Vec<f64> = random.fill(LENGTH);
                    let mut actual: Vec<f64> = vec![0.0; LENGTH];
                    let done: usize = unsafe { (level.cellular2)(&cellular, &xs, &ys, &mut actual) };
                    for index in done..LENGTH {
                        actual[index] = cellular.sample2(xs[index], ys[index]);
                    }
                    let expected: Vec<f64> = (0..LENGTH).map(|index| cellular.sample2(xs[index], ys[index])).collect();
                    assert_bits(&format!("{} cellular2 {distance}/{result}", level.name), &expected, &actual);
                    let done: usize = unsafe { (level.cellular3)(&cellular, &xs, &ys, &zs, &mut actual) };
                    for index in done..LENGTH {
                        actual[index] = cellular.sample3(xs[index], ys[index], zs[index]);
                    }
                    let expected: Vec<f64> = (0..LENGTH).map(|index| cellular.sample3(xs[index], ys[index], zs[index])).collect();
                    assert_bits(&format!("{} cellular3 {distance}/{result}", level.name), &expected, &actual);
                }
            }
        }
    }
}
