use std::arch::aarch64::*;

use crate::kernels::{
    val_coord2, Cellular, DISTANCE_EUCLIDEAN, DISTANCE_MANHATTAN, DISTANCE_NATURAL, F2, F3, G2, G3, G33, GRAD2_X_BYTES as GRAD2_X,
    GRAD2_Y_BYTES as GRAD2_Y, GRAD3_X_BYTES as GRAD3_X, GRAD3_Y_BYTES as GRAD3_Y, GRAD3_Z_BYTES as GRAD3_Z, LONGERP_HERMITE,
    LONGERP_QULONGIC, RETURN_CELL_VALUE, RETURN_DISTANCE, RETURN_DISTANCE2, RETURN_DISTANCE2_ADD, RETURN_DISTANCE2_DIV,
    RETURN_DISTANCE2_MUL, RETURN_DISTANCE2_SUB,
};
use crate::tables::{CELL_2D_X, CELL_2D_Y, CELL_3D_X, CELL_3D_Y, CELL_3D_Z};

const LANE_LOW: [u8; 16] = [16, 16, 16, 16, 16, 16, 0, 0, 16, 16, 16, 16, 16, 16, 4, 4];
const LANE_HIGH: [u8; 16] = [16, 16, 16, 16, 16, 16, 8, 8, 16, 16, 16, 16, 16, 16, 12, 12];
const LANE_BIAS: [u8; 16] = [0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0, 1, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0, 1];

#[derive(Clone, Copy)]
struct Pair {
    a: float64x2_t,
    b: float64x2_t,
}

#[inline(always)]
fn splat(value: f64) -> Pair {
    unsafe { Pair { a: vdupq_n_f64(value), b: vdupq_n_f64(value) } }
}

#[inline(always)]
fn add(left: Pair, right: Pair) -> Pair {
    unsafe { Pair { a: vaddq_f64(left.a, right.a), b: vaddq_f64(left.b, right.b) } }
}

#[inline(always)]
fn sub(left: Pair, right: Pair) -> Pair {
    unsafe { Pair { a: vsubq_f64(left.a, right.a), b: vsubq_f64(left.b, right.b) } }
}

#[inline(always)]
fn mul(left: Pair, right: Pair) -> Pair {
    unsafe { Pair { a: vmulq_f64(left.a, right.a), b: vmulq_f64(left.b, right.b) } }
}

#[derive(Clone, Copy)]
struct Mask {
    a: uint64x2_t,
    b: uint64x2_t,
}

#[inline(always)]
fn choose(mask: Mask, yes: Pair, no: Pair) -> Pair {
    unsafe { Pair { a: vbslq_f64(mask.a, yes.a, no.a), b: vbslq_f64(mask.b, yes.b, no.b) } }
}

#[inline(always)]
fn and(left: Mask, right: Mask) -> Mask {
    unsafe { Mask { a: vandq_u64(left.a, right.a), b: vandq_u64(left.b, right.b) } }
}

#[inline(always)]
fn or(left: Mask, right: Mask) -> Mask {
    unsafe { Mask { a: vorrq_u64(left.a, right.a), b: vorrq_u64(left.b, right.b) } }
}

#[inline(always)]
fn not(mask: Mask) -> Mask {
    unsafe {
        Mask {
            a: vreinterpretq_u64_u32(vmvnq_u32(vreinterpretq_u32_u64(mask.a))),
            b: vreinterpretq_u64_u32(vmvnq_u32(vreinterpretq_u32_u64(mask.b))),
        }
    }
}

#[inline(always)]
fn ge(left: Pair, right: Pair) -> Mask {
    unsafe { Mask { a: vcgeq_f64(left.a, right.a), b: vcgeq_f64(left.b, right.b) } }
}

#[inline(always)]
fn gt(left: Pair, right: Pair) -> Mask {
    unsafe { Mask { a: vcgtq_f64(left.a, right.a), b: vcgtq_f64(left.b, right.b) } }
}

#[inline(always)]
fn lt(left: Pair, right: Pair) -> Mask {
    unsafe { Mask { a: vcltq_f64(left.a, right.a), b: vcltq_f64(left.b, right.b) } }
}

#[inline(always)]
fn narrow_mask(mask: Mask) -> uint32x4_t {
    unsafe { vmovn_high_u64(vmovn_u64(mask.a), mask.b) }
}

#[inline(always)]
fn flag(mask: Mask) -> Pair {
    choose(mask, splat(1.0), splat(0.0))
}

#[derive(Clone, Copy)]
struct Cell {
    a: int64x2_t,
    b: int64x2_t,
}

#[inline(always)]
fn floor_cell(value: Pair) -> Cell {
    unsafe { Cell { a: vcvtmq_s64_f64(value.a), b: vcvtmq_s64_f64(value.b) } }
}

#[inline(always)]
fn widen(cell: Cell) -> Pair {
    unsafe { Pair { a: vcvtq_f64_s64(cell.a), b: vcvtq_f64_s64(cell.b) } }
}

#[inline(always)]
fn cell_add(left: Cell, right: Cell) -> Cell {
    unsafe { Cell { a: vaddq_s64(left.a, right.a), b: vaddq_s64(left.b, right.b) } }
}

#[inline(always)]
fn low_bits(cell: Cell) -> uint32x4_t {
    unsafe { vreinterpretq_u32_s32(vmovn_high_s64(vmovn_s64(cell.a), cell.b)) }
}

#[inline(always)]
fn hash2(seed: uint32x4_t, x: uint32x4_t, y: uint32x4_t) -> uint32x4_t {
    unsafe {
        let base: uint32x4_t = veorq_u32(veorq_u32(seed, vmulq_n_u32(x, 1619)), vmulq_n_u32(y, 31337));
        let cubed: uint32x4_t = vmulq_n_u32(vmulq_u32(vmulq_u32(base, base), base), 60493);
        veorq_u32(vshrq_n_u32::<13>(cubed), cubed)
    }
}

#[inline(always)]
fn hash3(seed: uint32x4_t, x: uint32x4_t, y: uint32x4_t, z: uint32x4_t) -> uint32x4_t {
    unsafe {
        let base: uint32x4_t =
            veorq_u32(veorq_u32(veorq_u32(seed, vmulq_n_u32(x, 1619)), vmulq_n_u32(y, 31337)), vmulq_n_u32(z, 6971));
        let cubed: uint32x4_t = vmulq_n_u32(vmulq_u32(vmulq_u32(base, base), base), 60493);
        veorq_u32(vshrq_n_u32::<13>(cubed), cubed)
    }
}

#[inline(always)]
fn plus_one(value: uint32x4_t) -> uint32x4_t {
    unsafe { vaddq_u32(value, vdupq_n_u32(1)) }
}

#[inline(always)]
fn plus_mask(value: uint32x4_t, mask: uint32x4_t) -> uint32x4_t {
    unsafe { vsubq_u32(value, mask) }
}

struct Lanes {
    low: uint8x16_t,
    high: uint8x16_t,
    bias: uint8x16_t,
}

impl Lanes {
    #[inline(always)]
    fn new() -> Lanes {
        unsafe { Lanes { low: vld1q_u8(LANE_LOW.as_ptr()), high: vld1q_u8(LANE_HIGH.as_ptr()), bias: vld1q_u8(LANE_BIAS.as_ptr()) } }
    }

    #[inline(always)]
    fn indices(&self, hash: uint32x4_t, mask: u32) -> (uint8x16_t, uint8x16_t) {
        unsafe {
            let doubled: uint8x16_t = vreinterpretq_u8_u32(vshlq_n_u32::<1>(vandq_u32(hash, vdupq_n_u32(mask))));
            (vaddq_u8(vqtbl1q_u8(doubled, self.low), self.bias), vaddq_u8(vqtbl1q_u8(doubled, self.high), self.bias))
        }
    }
}

struct Gradient2 {
    lanes: Lanes,
    x: uint8x16_t,
    y: uint8x16_t,
}

impl Gradient2 {
    #[inline(always)]
    fn new() -> Gradient2 {
        unsafe { Gradient2 { lanes: Lanes::new(), x: vld1q_u8(GRAD2_X.as_ptr()), y: vld1q_u8(GRAD2_Y.as_ptr()) } }
    }

    #[inline(always)]
    fn dot(&self, hash: uint32x4_t, x: Pair, y: Pair) -> Pair {
        unsafe {
            let (low, high): (uint8x16_t, uint8x16_t) = self.lanes.indices(hash, 7);
            let gx: Pair = Pair {
                a: vreinterpretq_f64_u8(vqtbl1q_u8(self.x, low)),
                b: vreinterpretq_f64_u8(vqtbl1q_u8(self.x, high)),
            };
            let gy: Pair = Pair {
                a: vreinterpretq_f64_u8(vqtbl1q_u8(self.y, low)),
                b: vreinterpretq_f64_u8(vqtbl1q_u8(self.y, high)),
            };
            add(mul(x, gx), mul(y, gy))
        }
    }
}

struct Gradient3 {
    lanes: Lanes,
    x: uint8x16x2_t,
    y: uint8x16x2_t,
    z: uint8x16x2_t,
}

impl Gradient3 {
    #[inline(always)]
    fn new() -> Gradient3 {
        unsafe {
            Gradient3 {
                lanes: Lanes::new(),
                x: vld1q_u8_x2(GRAD3_X.as_ptr()),
                y: vld1q_u8_x2(GRAD3_Y.as_ptr()),
                z: vld1q_u8_x2(GRAD3_Z.as_ptr()),
            }
        }
    }

    #[inline(always)]
    fn dot(&self, hash: uint32x4_t, x: Pair, y: Pair, z: Pair) -> Pair {
        unsafe {
            let (low, high): (uint8x16_t, uint8x16_t) = self.lanes.indices(hash, 15);
            let gx: Pair = Pair {
                a: vreinterpretq_f64_u8(vqtbl2q_u8(self.x, low)),
                b: vreinterpretq_f64_u8(vqtbl2q_u8(self.x, high)),
            };
            let gy: Pair = Pair {
                a: vreinterpretq_f64_u8(vqtbl2q_u8(self.y, low)),
                b: vreinterpretq_f64_u8(vqtbl2q_u8(self.y, high)),
            };
            let gz: Pair = Pair {
                a: vreinterpretq_f64_u8(vqtbl2q_u8(self.z, low)),
                b: vreinterpretq_f64_u8(vqtbl2q_u8(self.z, high)),
            };
            add(add(mul(x, gx), mul(y, gy)), mul(z, gz))
        }
    }
}

#[inline(always)]
fn corner(t: Pair, gradient: Pair) -> Pair {
    unsafe {
        let squared: Pair = mul(t, t);
        let value: Pair = mul(mul(squared, squared), gradient);
        let zero: float64x2_t = vdupq_n_f64(0.0);
        Pair { a: vbslq_f64(vcltzq_f64(t.a), zero, value.a), b: vbslq_f64(vcltzq_f64(t.b), zero, value.b) }
    }
}

#[inline(always)]
fn falloff2(x: Pair, y: Pair) -> Pair {
    sub(sub(splat(0.5), mul(x, x)), mul(y, y))
}

#[inline(always)]
fn falloff3(x: Pair, y: Pair, z: Pair) -> Pair {
    sub(sub(sub(splat(0.6), mul(x, x)), mul(y, y)), mul(z, z))
}

#[inline(always)]
fn simplex2_lanes(seed: i64, x: Pair, y: Pair) -> Pair {
    unsafe {
        let gradient: Gradient2 = Gradient2::new();
        let s: uint32x4_t = vdupq_n_u32(seed as u32);
        let g2: Pair = splat(G2);
        let skew: Pair = mul(add(x, y), splat(F2));
        let i: Cell = floor_cell(add(x, skew));
        let j: Cell = floor_cell(add(y, skew));
        let unskew: Pair = mul(widen(cell_add(i, j)), g2);
        let x0: Pair = sub(x, sub(widen(i), unskew));
        let y0: Pair = sub(y, sub(widen(j), unskew));
        let first: Mask = gt(x0, y0);
        let x1: Pair = add(sub(x0, flag(first)), g2);
        let y1: Pair = add(sub(y0, flag(not(first))), g2);
        let g2x2: Pair = splat(2.0 * G2);
        let x2: Pair = add(sub(x0, splat(1.0)), g2x2);
        let y2: Pair = add(sub(y0, splat(1.0)), g2x2);
        let iu: uint32x4_t = low_bits(i);
        let ju: uint32x4_t = low_bits(j);
        let mask: uint32x4_t = narrow_mask(first);
        let n0: Pair = corner(falloff2(x0, y0), gradient.dot(hash2(s, iu, ju), x0, y0));
        let n1: Pair = corner(
            falloff2(x1, y1),
            gradient.dot(hash2(s, plus_mask(iu, mask), plus_mask(ju, vmvnq_u32(mask))), x1, y1),
        );
        let n2: Pair = corner(falloff2(x2, y2), gradient.dot(hash2(s, plus_one(iu), plus_one(ju)), x2, y2));
        mul(splat(50.0), add(add(n0, n1), n2))
    }
}

#[inline(always)]
fn simplex3_lanes(seed: i64, x: Pair, y: Pair, z: Pair) -> Pair {
    unsafe {
        let gradient: Gradient3 = Gradient3::new();
        let s: uint32x4_t = vdupq_n_u32(seed as u32);
        let skew: Pair = mul(add(add(x, y), z), splat(F3));
        let i: Cell = floor_cell(add(x, skew));
        let j: Cell = floor_cell(add(y, skew));
        let k: Cell = floor_cell(add(z, skew));
        let unskew: Pair = mul(widen(cell_add(cell_add(i, j), k)), splat(G3));
        let x0: Pair = sub(x, sub(widen(i), unskew));
        let y0: Pair = sub(y, sub(widen(j), unskew));
        let z0: Pair = sub(z, sub(widen(k), unskew));
        let a: Mask = ge(x0, y0);
        let b1: Mask = ge(y0, z0);
        let c1: Mask = ge(x0, z0);
        let b2: Mask = lt(y0, z0);
        let c2: Mask = lt(x0, z0);
        let i1: Mask = and(a, or(b1, c1));
        let j1: Mask = and(not(a), not(b2));
        let k1: Mask = or(and(a, and(not(b1), not(c1))), and(not(a), b2));
        let i2: Mask = or(a, and(not(b2), not(c2)));
        let j2: Mask = or(not(a), b1);
        let k2: Mask = or(and(a, not(b1)), and(not(a), or(b2, c2)));
        let g3: Pair = splat(G3);
        let f3: Pair = splat(F3);
        let x1: Pair = add(sub(x0, flag(i1)), g3);
        let y1: Pair = add(sub(y0, flag(j1)), g3);
        let z1: Pair = add(sub(z0, flag(k1)), g3);
        let x2: Pair = add(sub(x0, flag(i2)), f3);
        let y2: Pair = add(sub(y0, flag(j2)), f3);
        let z2: Pair = add(sub(z0, flag(k2)), f3);
        let g33: Pair = splat(G33);
        let x3: Pair = add(x0, g33);
        let y3: Pair = add(y0, g33);
        let z3: Pair = add(z0, g33);
        let iu: uint32x4_t = low_bits(i);
        let ju: uint32x4_t = low_bits(j);
        let ku: uint32x4_t = low_bits(k);
        let n0: Pair = corner(falloff3(x0, y0, z0), gradient.dot(hash3(s, iu, ju, ku), x0, y0, z0));
        let n1: Pair = corner(
            falloff3(x1, y1, z1),
            gradient.dot(
                hash3(s, plus_mask(iu, narrow_mask(i1)), plus_mask(ju, narrow_mask(j1)), plus_mask(ku, narrow_mask(k1))),
                x1,
                y1,
                z1,
            ),
        );
        let n2: Pair = corner(
            falloff3(x2, y2, z2),
            gradient.dot(
                hash3(s, plus_mask(iu, narrow_mask(i2)), plus_mask(ju, narrow_mask(j2)), plus_mask(ku, narrow_mask(k2))),
                x2,
                y2,
                z2,
            ),
        );
        let n3: Pair = corner(
            falloff3(x3, y3, z3),
            gradient.dot(hash3(s, plus_one(iu), plus_one(ju), plus_one(ku)), x3, y3, z3),
        );
        mul(splat(32.0), add(add(add(n0, n1), n2), n3))
    }
}

#[inline(always)]
fn lerp(a: Pair, b: Pair, t: Pair) -> Pair {
    add(a, mul(t, sub(b, a)))
}

#[inline(always)]
fn interpolant<const MODE: i32>(t: Pair) -> Pair {
    match MODE {
        LONGERP_HERMITE => mul(mul(t, t), sub(splat(3.0), mul(splat(2.0), t))),
        LONGERP_QULONGIC => mul(mul(mul(t, t), t), add(mul(t, sub(mul(t, splat(6.0)), splat(15.0))), splat(10.0))),
        _ => t,
    }
}

#[inline(always)]
fn perlin2_lanes<const MODE: i32>(seed: i64, x: Pair, y: Pair) -> Pair {
    unsafe {
        let gradient: Gradient2 = Gradient2::new();
        let s: uint32x4_t = vdupq_n_u32(seed as u32);
        let x0: Cell = floor_cell(x);
        let y0: Cell = floor_cell(y);
        let xd0: Pair = sub(x, widen(x0));
        let yd0: Pair = sub(y, widen(y0));
        let xs: Pair = interpolant::<MODE>(xd0);
        let ys: Pair = interpolant::<MODE>(yd0);
        let xd1: Pair = sub(xd0, splat(1.0));
        let yd1: Pair = sub(yd0, splat(1.0));
        let xu: uint32x4_t = low_bits(x0);
        let yu: uint32x4_t = low_bits(y0);
        let xv: uint32x4_t = plus_one(xu);
        let yv: uint32x4_t = plus_one(yu);
        let xf0: Pair = lerp(gradient.dot(hash2(s, xu, yu), xd0, yd0), gradient.dot(hash2(s, xv, yu), xd1, yd0), xs);
        let xf1: Pair = lerp(gradient.dot(hash2(s, xu, yv), xd0, yd1), gradient.dot(hash2(s, xv, yv), xd1, yd1), xs);
        lerp(xf0, xf1, ys)
    }
}

#[inline(always)]
fn perlin3_lanes<const MODE: i32>(seed: i64, x: Pair, y: Pair, z: Pair) -> Pair {
    unsafe {
        let gradient: Gradient3 = Gradient3::new();
        let s: uint32x4_t = vdupq_n_u32(seed as u32);
        let x0: Cell = floor_cell(x);
        let y0: Cell = floor_cell(y);
        let z0: Cell = floor_cell(z);
        let xd0: Pair = sub(x, widen(x0));
        let yd0: Pair = sub(y, widen(y0));
        let zd0: Pair = sub(z, widen(z0));
        let xs: Pair = interpolant::<MODE>(xd0);
        let ys: Pair = interpolant::<MODE>(yd0);
        let zs: Pair = interpolant::<MODE>(zd0);
        let xd1: Pair = sub(xd0, splat(1.0));
        let yd1: Pair = sub(yd0, splat(1.0));
        let zd1: Pair = sub(zd0, splat(1.0));
        let xu: uint32x4_t = low_bits(x0);
        let yu: uint32x4_t = low_bits(y0);
        let zu: uint32x4_t = low_bits(z0);
        let xv: uint32x4_t = plus_one(xu);
        let yv: uint32x4_t = plus_one(yu);
        let zv: uint32x4_t = plus_one(zu);
        let xf00: Pair = lerp(
            gradient.dot(hash3(s, xu, yu, zu), xd0, yd0, zd0),
            gradient.dot(hash3(s, xv, yu, zu), xd1, yd0, zd0),
            xs,
        );
        let xf10: Pair = lerp(
            gradient.dot(hash3(s, xu, yv, zu), xd0, yd1, zd0),
            gradient.dot(hash3(s, xv, yv, zu), xd1, yd1, zd0),
            xs,
        );
        let xf01: Pair = lerp(
            gradient.dot(hash3(s, xu, yu, zv), xd0, yd0, zd1),
            gradient.dot(hash3(s, xv, yu, zv), xd1, yd0, zd1),
            xs,
        );
        let xf11: Pair = lerp(
            gradient.dot(hash3(s, xu, yv, zv), xd0, yd1, zd1),
            gradient.dot(hash3(s, xv, yv, zv), xd1, yd1, zd1),
            xs,
        );
        lerp(lerp(xf00, xf10, ys), lerp(xf01, xf11, ys), zs)
    }
}

#[inline(always)]
fn load(values: &[f64], offset: usize) -> Pair {
    unsafe { Pair { a: vld1q_f64(values.as_ptr().add(offset)), b: vld1q_f64(values.as_ptr().add(offset + 2)) } }
}

#[inline(always)]
fn store(values: &mut [f64], offset: usize, pair: Pair) {
    unsafe {
        vst1q_f64(values.as_mut_ptr().add(offset), pair.a);
        vst1q_f64(values.as_mut_ptr().add(offset + 2), pair.b);
    }
}

#[inline(always)]
fn round_cell(value: Pair) -> Cell {
    unsafe {
        let shifted: Pair = choose(ge(value, splat(0.0)), add(value, splat(0.5)), sub(value, splat(0.5)));
        Cell { a: vcvtq_s64_f64(shifted.a), b: vcvtq_s64_f64(shifted.b) }
    }
}

#[inline(always)]
fn offset(cell: Cell, delta: i64) -> Cell {
    unsafe { Cell { a: vaddq_s64(cell.a, vdupq_n_s64(delta)), b: vaddq_s64(cell.b, vdupq_n_s64(delta)) } }
}

#[inline(always)]
fn choose_cell(mask: Mask, yes: Cell, no: Cell) -> Cell {
    unsafe { Cell { a: vbslq_s64(mask.a, yes.a, no.a), b: vbslq_s64(mask.b, yes.b, no.b) } }
}

#[inline(always)]
fn absolute(value: Pair) -> Pair {
    unsafe { Pair { a: vabsq_f64(value.a), b: vabsq_f64(value.b) } }
}

#[inline(always)]
fn divide(left: Pair, right: Pair) -> Pair {
    unsafe { Pair { a: vdivq_f64(left.a, right.a), b: vdivq_f64(left.b, right.b) } }
}

#[inline(always)]
fn java_max(a: Pair, b: Pair) -> Pair {
    unsafe {
        let ordered: Mask = Mask { a: vceqq_f64(a.a, a.a), b: vceqq_f64(a.b, a.b) };
        choose(ordered, choose(ge(a, b), a, b), a)
    }
}

#[inline(always)]
fn gather(table: &[f64; 256], hash: uint32x4_t) -> Pair {
    unsafe {
        let index: uint32x4_t = vandq_u32(hash, vdupq_n_u32(255));
        let low: [f64; 2] = [
            *table.get_unchecked(vgetq_lane_u32::<0>(index) as usize),
            *table.get_unchecked(vgetq_lane_u32::<1>(index) as usize),
        ];
        let high: [f64; 2] = [
            *table.get_unchecked(vgetq_lane_u32::<2>(index) as usize),
            *table.get_unchecked(vgetq_lane_u32::<3>(index) as usize),
        ];
        Pair { a: vld1q_f64(low.as_ptr()), b: vld1q_f64(high.as_ptr()) }
    }
}

#[inline(always)]
fn measure2<const DISTANCE: i32>(x: Pair, y: Pair) -> Pair {
    match DISTANCE {
        DISTANCE_EUCLIDEAN => add(mul(x, x), mul(y, y)),
        DISTANCE_MANHATTAN => add(absolute(x), absolute(y)),
        _ => add(add(absolute(x), absolute(y)), add(mul(x, x), mul(y, y))),
    }
}

#[inline(always)]
fn measure3<const DISTANCE: i32>(x: Pair, y: Pair, z: Pair) -> Pair {
    match DISTANCE {
        DISTANCE_EUCLIDEAN => add(add(mul(x, x), mul(y, y)), mul(z, z)),
        DISTANCE_MANHATTAN => add(add(absolute(x), absolute(y)), absolute(z)),
        _ => add(add(add(absolute(x), absolute(y)), absolute(z)), add(add(mul(x, x), mul(y, y)), mul(z, z))),
    }
}

#[inline(always)]
fn finite(value: Pair) -> bool {
    unsafe {
        let limit: float64x2_t = vdupq_n_f64(f64::MAX);
        let a: uint64x2_t = vcleq_f64(vabsq_f64(value.a), limit);
        let b: uint64x2_t = vcleq_f64(vabsq_f64(value.b), limit);
        vminvq_u32(vreinterpretq_u32_u64(vandq_u64(a, b))) == u32::MAX
    }
}

#[inline(always)]
fn lanes(pair: Pair) -> [f64; 4] {
    let mut values: [f64; 4] = [0.0; 4];
    store(&mut values, 0, pair);
    values
}

#[inline(always)]
fn cell_lanes(cell: Cell) -> [i64; 4] {
    unsafe {
        [vgetq_lane_s64::<0>(cell.a), vgetq_lane_s64::<1>(cell.a), vgetq_lane_s64::<0>(cell.b), vgetq_lane_s64::<1>(cell.b)]
    }
}

#[inline(always)]
fn mask_lanes(mask: Mask) -> [bool; 4] {
    unsafe {
        [
            vgetq_lane_u64::<0>(mask.a) != 0,
            vgetq_lane_u64::<1>(mask.a) != 0,
            vgetq_lane_u64::<0>(mask.b) != 0,
            vgetq_lane_u64::<1>(mask.b) != 0,
        ]
    }
}

#[inline(always)]
fn edge_result(result: i32, distance: Pair, distance2: Pair) -> Pair {
    let one: Pair = splat(1.0);
    match result {
        RETURN_DISTANCE2 => sub(distance2, one),
        RETURN_DISTANCE2_ADD => sub(add(distance2, distance), one),
        RETURN_DISTANCE2_SUB => sub(sub(distance2, distance), one),
        RETURN_DISTANCE2_MUL => sub(mul(distance2, distance), one),
        RETURN_DISTANCE2_DIV => sub(divide(distance, distance2), one),
        _ => splat(0.0),
    }
}

fn cellular2_lanes(cellular: &Cellular, x: Pair, y: Pair) -> Option<Pair> {
    if !finite(x) || !finite(y) {
        return None;
    }
    Some(match cellular.distance {
        DISTANCE_EUCLIDEAN => cellular2_distance::<DISTANCE_EUCLIDEAN>(cellular, x, y),
        DISTANCE_MANHATTAN => cellular2_distance::<DISTANCE_MANHATTAN>(cellular, x, y),
        _ => cellular2_distance::<DISTANCE_NATURAL>(cellular, x, y),
    })
}

fn cellular3_lanes(cellular: &Cellular, x: Pair, y: Pair, z: Pair) -> Option<Pair> {
    if !finite(x) || !finite(y) || !finite(z) {
        return None;
    }
    Some(match cellular.distance {
        DISTANCE_EUCLIDEAN => cellular3_distance::<DISTANCE_EUCLIDEAN>(cellular, x, y, z),
        DISTANCE_MANHATTAN => cellular3_distance::<DISTANCE_MANHATTAN>(cellular, x, y, z),
        _ => cellular3_distance::<DISTANCE_NATURAL>(cellular, x, y, z),
    })
}

#[inline(always)]
fn cellular2_distance<const DISTANCE: i32>(cellular: &Cellular, x: Pair, y: Pair) -> Pair {
    unsafe {
        let seed: uint32x4_t = vdupq_n_u32(cellular.seed as u32);
        let xr: Cell = round_cell(x);
        let yr: Cell = round_cell(y);
        let single: bool = cellular.result == RETURN_CELL_VALUE || cellular.result == RETURN_DISTANCE;
        let mut distance: Pair = splat(999999.0);
        let mut distance2: Pair = splat(999999.0);
        let mut xc: Cell = Cell { a: vdupq_n_s64(0), b: vdupq_n_s64(0) };
        let mut yc: Cell = xc;
        for dx in -1i64..=1 {
            let xi: Cell = offset(xr, dx);
            let base_x: Pair = sub(widen(xi), x);
            let xu: uint32x4_t = low_bits(xi);
            for dy in -1i64..=1 {
                let yi: Cell = offset(yr, dy);
                let base_y: Pair = sub(widen(yi), y);
                let hash: uint32x4_t = hash2(seed, xu, low_bits(yi));
                let candidate: Pair =
                    measure2::<DISTANCE>(add(base_x, gather(&CELL_2D_X, hash)), add(base_y, gather(&CELL_2D_Y, hash)));
                let closer: Mask = lt(candidate, distance);
                if single {
                    xc = choose_cell(closer, xi, xc);
                    yc = choose_cell(closer, yi, yc);
                } else {
                    distance2 = choose(closer, distance, choose(lt(candidate, distance2), candidate, distance2));
                }
                distance = choose(closer, candidate, distance);
            }
        }
        if single {
            if cellular.result == RETURN_DISTANCE {
                return sub(distance, splat(1.0));
            }
            let xs: [i64; 4] = cell_lanes(xc);
            let ys: [i64; 4] = cell_lanes(yc);
            let values: [f64; 4] = [val_coord2(0, xs[0], ys[0]), val_coord2(0, xs[1], ys[1]), val_coord2(0, xs[2], ys[2]), val_coord2(0, xs[3], ys[3])];
            return load(&values, 0);
        }
        let zero: Pair = splat(0.0);
        let boundary: Pair = sub(splat(1.55), java_max(absolute(sub(x, widen(xr))), absolute(sub(y, widen(yr)))));
        let refine: [bool; 4] = mask_lanes(lt(measure3::<DISTANCE>(boundary, zero, zero), distance2));
        let result: Pair = edge_result(cellular.result, distance, distance2);
        if !(refine[0] | refine[1] | refine[2] | refine[3]) {
            return result;
        }
        let mut values: [f64; 4] = lanes(result);
        let xs: [f64; 4] = lanes(x);
        let ys: [f64; 4] = lanes(y);
        let xrs: [i64; 4] = cell_lanes(xr);
        let yrs: [i64; 4] = cell_lanes(yr);
        let distances: [f64; 4] = lanes(distance);
        let distances2: [f64; 4] = lanes(distance2);
        for lane in 0..4 {
            if refine[lane] {
                values[lane] = cellular.refine2(xs[lane], ys[lane], xrs[lane], yrs[lane], distances[lane], distances2[lane]);
            }
        }
        load(&values, 0)
    }
}

#[inline(always)]
fn cellular3_distance<const DISTANCE: i32>(cellular: &Cellular, x: Pair, y: Pair, z: Pair) -> Pair {
    unsafe {
        let seed: uint32x4_t = vdupq_n_u32(cellular.seed as u32);
        let xr: Cell = round_cell(x);
        let yr: Cell = round_cell(y);
        let zr: Cell = round_cell(z);
        let mut distance: Pair = splat(999999.0);
        let mut distance2: Pair = splat(999999.0);
        for dx in -1i64..=1 {
            let xi: Cell = offset(xr, dx);
            let base_x: Pair = sub(widen(xi), x);
            let xu: uint32x4_t = low_bits(xi);
            for dy in -1i64..=1 {
                let yi: Cell = offset(yr, dy);
                let base_y: Pair = sub(widen(yi), y);
                let yu: uint32x4_t = low_bits(yi);
                for dz in -1i64..=1 {
                    let zi: Cell = offset(zr, dz);
                    let base_z: Pair = sub(widen(zi), z);
                    let hash: uint32x4_t = hash3(seed, xu, yu, low_bits(zi));
                    let candidate: Pair = measure3::<DISTANCE>(
                        add(base_x, gather(&CELL_3D_X, hash)),
                        add(base_y, gather(&CELL_3D_Y, hash)),
                        add(base_z, gather(&CELL_3D_Z, hash)),
                    );
                    let closer: Mask = lt(candidate, distance);
                    distance2 = choose(closer, distance, choose(lt(candidate, distance2), candidate, distance2));
                    distance = choose(closer, candidate, distance);
                }
            }
        }
        let zero: Pair = splat(0.0);
        let boundary: Pair = sub(
            splat(1.55),
            java_max(absolute(sub(x, widen(xr))), java_max(absolute(sub(y, widen(yr))), absolute(sub(z, widen(zr))))),
        );
        let refine: [bool; 4] = mask_lanes(lt(measure3::<DISTANCE>(boundary, zero, zero), distance2));
        let result: Pair = edge_result(cellular.result, distance, distance2);
        if !(refine[0] | refine[1] | refine[2] | refine[3]) {
            return result;
        }
        let mut values: [f64; 4] = lanes(result);
        let xs: [f64; 4] = lanes(x);
        let ys: [f64; 4] = lanes(y);
        let zs: [f64; 4] = lanes(z);
        let xrs: [i64; 4] = cell_lanes(xr);
        let yrs: [i64; 4] = cell_lanes(yr);
        let zrs: [i64; 4] = cell_lanes(zr);
        let distances: [f64; 4] = lanes(distance);
        let distances2: [f64; 4] = lanes(distance2);
        for lane in 0..4 {
            if refine[lane] {
                values[lane] =
                    cellular.refine3(xs[lane], ys[lane], zs[lane], xrs[lane], yrs[lane], zrs[lane], distances[lane], distances2[lane]);
            }
        }
        load(&values, 0)
    }
}

pub fn simplex2(seed: i64, xs: &[f64], ys: &[f64], out: &mut [f64]) -> usize {
    let quads: usize = out.len() / 4 * 4;
    for start in (0..quads).step_by(4) {
        store(out, start, simplex2_lanes(seed, load(xs, start), load(ys, start)));
    }
    quads
}

pub fn simplex3(seed: i64, xs: &[f64], ys: &[f64], zs: &[f64], out: &mut [f64]) -> usize {
    let quads: usize = out.len() / 4 * 4;
    for start in (0..quads).step_by(4) {
        store(out, start, simplex3_lanes(seed, load(xs, start), load(ys, start), load(zs, start)));
    }
    quads
}

pub fn perlin2<const MODE: i32>(seed: i64, xs: &[f64], ys: &[f64], out: &mut [f64]) -> usize {
    let quads: usize = out.len() / 4 * 4;
    for start in (0..quads).step_by(4) {
        store(out, start, perlin2_lanes::<MODE>(seed, load(xs, start), load(ys, start)));
    }
    quads
}

pub fn perlin3<const MODE: i32>(seed: i64, xs: &[f64], ys: &[f64], zs: &[f64], out: &mut [f64]) -> usize {
    let quads: usize = out.len() / 4 * 4;
    for start in (0..quads).step_by(4) {
        store(out, start, perlin3_lanes::<MODE>(seed, load(xs, start), load(ys, start), load(zs, start)));
    }
    quads
}

pub fn cellular2(cellular: &Cellular, xs: &[f64], ys: &[f64], out: &mut [f64]) -> usize {
    let quads: usize = out.len() / 4 * 4;
    for start in (0..quads).step_by(4) {
        match cellular2_lanes(cellular, load(xs, start), load(ys, start)) {
            Some(values) => store(out, start, values),
            None => {
                for index in start..start + 4 {
                    out[index] = cellular.sample2(xs[index], ys[index]);
                }
            }
        }
    }
    quads
}

pub fn cellular3(cellular: &Cellular, xs: &[f64], ys: &[f64], zs: &[f64], out: &mut [f64]) -> usize {
    if cellular.result == RETURN_CELL_VALUE || cellular.result == RETURN_DISTANCE {
        return 0;
    }
    let quads: usize = out.len() / 4 * 4;
    for start in (0..quads).step_by(4) {
        match cellular3_lanes(cellular, load(xs, start), load(ys, start), load(zs, start)) {
            Some(values) => store(out, start, values),
            None => {
                for index in start..start + 4 {
                    out[index] = cellular.sample3(xs[index], ys[index], zs[index]);
                }
            }
        }
    }
    quads
}
