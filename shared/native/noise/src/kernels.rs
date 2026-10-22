use crate::tables::{CELL_2D_X, CELL_2D_Y, CELL_3D_X, CELL_3D_Y, CELL_3D_Z};

pub const F2: f64 = f64::from_bits(0x3fd76cf5d0b09954);
pub const G2: f64 = f64::from_bits(0x3fcb0cb174df99c8);
pub const F3: f64 = 1.0 / 3.0;
pub const G3: f64 = 1.0 / 6.0;
pub const G33: f64 = G3 * 3.0 - 1.0;

const GRAD2: [i8; 16] = [-1, -1, 1, -1, -1, 1, 1, 1, 0, -1, -1, 0, 0, 1, 1, 0];
const GRAD3: [i8; 48] = [
    1, 1, 0, -1, 1, 0, 1, -1, 0, -1, -1, 0, 1, 0, 1, -1, 0, 1, 1, 0, -1, -1, 0, -1, 0, 1, 1, 0, -1, 1, 0, 1, -1, 0, -1, -1,
    1, 1, 0, 0, -1, 1, -1, 1, 0, 0, -1, -1,
];
pub const GRAD2_X: u32 = pack(&GRAD2, 2, 0, 8);
pub const GRAD2_Y: u32 = pack(&GRAD2, 2, 1, 8);
pub const GRAD3_X: u32 = pack(&GRAD3, 3, 0, 16);
pub const GRAD3_Y: u32 = pack(&GRAD3, 3, 1, 16);
pub const GRAD3_Z: u32 = pack(&GRAD3, 3, 2, 16);

pub const LONGERP_LINEAR: i32 = 0;
pub const LONGERP_HERMITE: i32 = 1;
pub const LONGERP_QULONGIC: i32 = 2;

pub const FRACTAL_FBM: i32 = 0;
pub const FRACTAL_BILLOW: i32 = 1;
pub const FRACTAL_RIGID_MULTI: i32 = 2;

pub const DISTANCE_EUCLIDEAN: i32 = 0;
pub const DISTANCE_MANHATTAN: i32 = 1;
pub const DISTANCE_NATURAL: i32 = 2;

pub const RETURN_CELL_VALUE: i32 = 0;
pub const RETURN_NOISE_LOOKUP: i32 = 1;
pub const RETURN_DISTANCE: i32 = 2;
pub const RETURN_DISTANCE2: i32 = 3;
pub const RETURN_DISTANCE2_ADD: i32 = 4;
pub const RETURN_DISTANCE2_SUB: i32 = 5;
pub const RETURN_DISTANCE2_MUL: i32 = 6;
pub const RETURN_DISTANCE2_DIV: i32 = 7;

const fn pack(values: &[i8], stride: usize, offset: usize, count: usize) -> u32 {
    let mut packed: u32 = 0;
    let mut index: usize = 0;
    while index < count {
        let bits: u32 = (values[index * stride + offset] as i32 as u32) & 3;
        packed |= bits << (index * 2);
        index += 1;
    }
    packed
}

#[inline(always)]
fn unpack(packed: u32, index: u32) -> f64 {
    let bits: u32 = (packed >> (index << 1)) & 3;
    (((bits << 30) as i32) >> 30) as f64
}

#[inline(always)]
fn low_hash2(seed: u32, x: u32, y: u32) -> u32 {
    let base: u32 = seed ^ x.wrapping_mul(1619) ^ y.wrapping_mul(31337);
    let cubed: u32 = base.wrapping_mul(base).wrapping_mul(base).wrapping_mul(60493);
    (cubed >> 13) ^ cubed
}

#[inline(always)]
fn low_hash3(seed: u32, x: u32, y: u32, z: u32) -> u32 {
    let base: u32 = seed ^ x.wrapping_mul(1619) ^ y.wrapping_mul(31337) ^ z.wrapping_mul(6971);
    let cubed: u32 = base.wrapping_mul(base).wrapping_mul(base).wrapping_mul(60493);
    (cubed >> 13) ^ cubed
}

#[inline(always)]
pub fn val_coord2(seed: i64, x: i64, y: i64) -> f64 {
    let base: i64 = seed ^ x.wrapping_mul(1619) ^ y.wrapping_mul(31337);
    base.wrapping_mul(base).wrapping_mul(base).wrapping_mul(60493) as f64 / (i64::MAX as f64)
}

#[inline(always)]
fn val_coord3(seed: i64, x: i64, y: i64, z: i64) -> f64 {
    let base: i64 = seed ^ x.wrapping_mul(1619) ^ y.wrapping_mul(31337) ^ z.wrapping_mul(6971);
    base.wrapping_mul(base).wrapping_mul(base).wrapping_mul(60493) as f64 / (i64::MAX as f64)
}

#[inline(always)]
fn gradient2(hash: u32, x: f64, y: f64) -> f64 {
    let index: u32 = hash & 7;
    x * unpack(GRAD2_X, index) + y * unpack(GRAD2_Y, index)
}

#[inline(always)]
fn gradient3(hash: u32, x: f64, y: f64, z: f64) -> f64 {
    let index: u32 = hash & 15;
    x * unpack(GRAD3_X, index) + y * unpack(GRAD3_Y, index) + z * unpack(GRAD3_Z, index)
}

#[inline(always)]
fn simplex_corner(t: f64, gradient: f64) -> f64 {
    let squared: f64 = t * t;
    let value: f64 = squared * squared * gradient;
    if t < 0.0 { 0.0 } else { value }
}

#[inline(always)]
fn select(condition: bool, yes: f64, no: f64) -> f64 {
    if condition { yes } else { no }
}

#[inline(always)]
pub fn simplex2(seed: i64, x: f64, y: f64) -> f64 {
    let s: u32 = seed as u32;
    let skew: f64 = (x + y) * F2;
    let i: i64 = (x + skew).floor() as i64;
    let j: i64 = (y + skew).floor() as i64;
    let unskew: f64 = i.wrapping_add(j) as f64 * G2;
    let x0: f64 = x - (i as f64 - unskew);
    let y0: f64 = y - (j as f64 - unskew);
    let first: bool = x0 > y0;
    let x1: f64 = x0 - select(first, 1.0, 0.0) + G2;
    let y1: f64 = y0 - select(first, 0.0, 1.0) + G2;
    let x2: f64 = x0 - 1.0 + 2.0 * G2;
    let y2: f64 = y0 - 1.0 + 2.0 * G2;
    let iu: u32 = i as u32;
    let ju: u32 = j as u32;
    let n0: f64 = simplex_corner(0.5 - x0 * x0 - y0 * y0, gradient2(low_hash2(s, iu, ju), x0, y0));
    let n1: f64 = simplex_corner(
        0.5 - x1 * x1 - y1 * y1,
        gradient2(low_hash2(s, iu.wrapping_add(first as u32), ju.wrapping_add((!first) as u32)), x1, y1),
    );
    let n2: f64 = simplex_corner(
        0.5 - x2 * x2 - y2 * y2,
        gradient2(low_hash2(s, iu.wrapping_add(1), ju.wrapping_add(1)), x2, y2),
    );
    50.0 * (n0 + n1 + n2)
}

#[inline(always)]
pub fn simplex3(seed: i64, x: f64, y: f64, z: f64) -> f64 {
    let s: u32 = seed as u32;
    let skew: f64 = (x + y + z) * F3;
    let i: i64 = (x + skew).floor() as i64;
    let j: i64 = (y + skew).floor() as i64;
    let k: i64 = (z + skew).floor() as i64;
    let unskew: f64 = i.wrapping_add(j).wrapping_add(k) as f64 * G3;
    let x0: f64 = x - (i as f64 - unskew);
    let y0: f64 = y - (j as f64 - unskew);
    let z0: f64 = z - (k as f64 - unskew);
    let a: bool = x0 >= y0;
    let b1: bool = y0 >= z0;
    let c1: bool = x0 >= z0;
    let b2: bool = y0 < z0;
    let c2: bool = x0 < z0;
    let i1: bool = a & (b1 | c1);
    let j1: bool = !a & !b2;
    let k1: bool = if a { !b1 & !c1 } else { b2 };
    let i2: bool = a | (!b2 & !c2);
    let j2: bool = !a | b1;
    let k2: bool = if a { !b1 } else { b2 | c2 };
    let x1: f64 = x0 - select(i1, 1.0, 0.0) + G3;
    let y1: f64 = y0 - select(j1, 1.0, 0.0) + G3;
    let z1: f64 = z0 - select(k1, 1.0, 0.0) + G3;
    let x2: f64 = x0 - select(i2, 1.0, 0.0) + F3;
    let y2: f64 = y0 - select(j2, 1.0, 0.0) + F3;
    let z2: f64 = z0 - select(k2, 1.0, 0.0) + F3;
    let x3: f64 = x0 + G33;
    let y3: f64 = y0 + G33;
    let z3: f64 = z0 + G33;
    let iu: u32 = i as u32;
    let ju: u32 = j as u32;
    let ku: u32 = k as u32;
    let n0: f64 = simplex_corner(
        0.6 - x0 * x0 - y0 * y0 - z0 * z0,
        gradient3(low_hash3(s, iu, ju, ku), x0, y0, z0),
    );
    let n1: f64 = simplex_corner(
        0.6 - x1 * x1 - y1 * y1 - z1 * z1,
        gradient3(
            low_hash3(s, iu.wrapping_add(i1 as u32), ju.wrapping_add(j1 as u32), ku.wrapping_add(k1 as u32)),
            x1,
            y1,
            z1,
        ),
    );
    let n2: f64 = simplex_corner(
        0.6 - x2 * x2 - y2 * y2 - z2 * z2,
        gradient3(
            low_hash3(s, iu.wrapping_add(i2 as u32), ju.wrapping_add(j2 as u32), ku.wrapping_add(k2 as u32)),
            x2,
            y2,
            z2,
        ),
    );
    let n3: f64 = simplex_corner(
        0.6 - x3 * x3 - y3 * y3 - z3 * z3,
        gradient3(low_hash3(s, iu.wrapping_add(1), ju.wrapping_add(1), ku.wrapping_add(1)), x3, y3, z3),
    );
    32.0 * (n0 + n1 + n2 + n3)
}

#[inline(always)]
fn lerp(a: f64, b: f64, t: f64) -> f64 {
    a + t * (b - a)
}

#[inline(always)]
fn interpolant<const MODE: i32>(t: f64) -> f64 {
    match MODE {
        LONGERP_HERMITE => t * t * (3.0 - 2.0 * t),
        LONGERP_QULONGIC => t * t * t * (t * (t * 6.0 - 15.0) + 10.0),
        _ => t,
    }
}

#[inline(always)]
pub fn perlin2<const MODE: i32>(seed: i64, x: f64, y: f64) -> f64 {
    let s: u32 = seed as u32;
    let x0: i64 = x.floor() as i64;
    let y0: i64 = y.floor() as i64;
    let xd0: f64 = x - x0 as f64;
    let yd0: f64 = y - y0 as f64;
    let xs: f64 = interpolant::<MODE>(xd0);
    let ys: f64 = interpolant::<MODE>(yd0);
    let xd1: f64 = xd0 - 1.0;
    let yd1: f64 = yd0 - 1.0;
    let xu: u32 = x0 as u32;
    let yu: u32 = y0 as u32;
    let xv: u32 = xu.wrapping_add(1);
    let yv: u32 = yu.wrapping_add(1);
    let xf0: f64 = lerp(gradient2(low_hash2(s, xu, yu), xd0, yd0), gradient2(low_hash2(s, xv, yu), xd1, yd0), xs);
    let xf1: f64 = lerp(gradient2(low_hash2(s, xu, yv), xd0, yd1), gradient2(low_hash2(s, xv, yv), xd1, yd1), xs);
    lerp(xf0, xf1, ys)
}

#[inline(always)]
pub fn perlin3<const MODE: i32>(seed: i64, x: f64, y: f64, z: f64) -> f64 {
    let s: u32 = seed as u32;
    let x0: i64 = x.floor() as i64;
    let y0: i64 = y.floor() as i64;
    let z0: i64 = z.floor() as i64;
    let xd0: f64 = x - x0 as f64;
    let yd0: f64 = y - y0 as f64;
    let zd0: f64 = z - z0 as f64;
    let xs: f64 = interpolant::<MODE>(xd0);
    let ys: f64 = interpolant::<MODE>(yd0);
    let zs: f64 = interpolant::<MODE>(zd0);
    let xd1: f64 = xd0 - 1.0;
    let yd1: f64 = yd0 - 1.0;
    let zd1: f64 = zd0 - 1.0;
    let xu: u32 = x0 as u32;
    let yu: u32 = y0 as u32;
    let zu: u32 = z0 as u32;
    let xv: u32 = xu.wrapping_add(1);
    let yv: u32 = yu.wrapping_add(1);
    let zv: u32 = zu.wrapping_add(1);
    let xf00: f64 = lerp(
        gradient3(low_hash3(s, xu, yu, zu), xd0, yd0, zd0),
        gradient3(low_hash3(s, xv, yu, zu), xd1, yd0, zd0),
        xs,
    );
    let xf10: f64 = lerp(
        gradient3(low_hash3(s, xu, yv, zu), xd0, yd1, zd0),
        gradient3(low_hash3(s, xv, yv, zu), xd1, yd1, zd0),
        xs,
    );
    let xf01: f64 = lerp(
        gradient3(low_hash3(s, xu, yu, zv), xd0, yd0, zd1),
        gradient3(low_hash3(s, xv, yu, zv), xd1, yd0, zd1),
        xs,
    );
    let xf11: f64 = lerp(
        gradient3(low_hash3(s, xu, yv, zv), xd0, yd1, zd1),
        gradient3(low_hash3(s, xv, yv, zv), xd1, yd1, zd1),
        xs,
    );
    let yf0: f64 = lerp(xf00, xf10, ys);
    let yf1: f64 = lerp(xf01, xf11, ys);
    lerp(yf0, yf1, zs)
}

#[inline(always)]
pub fn white2(seed: i64, x: f64, y: f64) -> f64 {
    val_coord2(seed, double_cast(x), double_cast(y))
}

#[inline(always)]
pub fn white3(seed: i64, x: f64, y: f64, z: f64) -> f64 {
    val_coord3(seed, double_cast(x), double_cast(y), double_cast(z))
}

#[inline(always)]
fn double_cast(value: f64) -> i64 {
    let bits: i64 = value.to_bits() as i64;
    bits ^ (bits >> 16)
}

#[inline(always)]
fn fast_round(value: f64) -> i64 {
    if value >= 0.0 { (value + 0.5) as i64 } else { (value - 0.5) as i64 }
}

#[inline(always)]
pub fn java_max(a: f64, b: f64) -> f64 {
    if a.is_nan() {
        return a;
    }
    if a == 0.0 && b == 0.0 && a.to_bits() == (-0.0f64).to_bits() {
        return b;
    }
    if a >= b { a } else { b }
}

#[inline(always)]
pub fn java_min(a: f64, b: f64) -> f64 {
    if a.is_nan() {
        return a;
    }
    if a == 0.0 && b == 0.0 && b.to_bits() == (-0.0f64).to_bits() {
        return b;
    }
    if a <= b { a } else { b }
}

#[derive(Clone, Copy)]
pub struct Cellular {
    pub seed: i64,
    pub distance: i32,
    pub result: i32,
}

impl Cellular {
    #[inline(always)]
    fn measure(&self, x: f64, y: f64, z: f64) -> f64 {
        match self.distance {
            DISTANCE_EUCLIDEAN => x * x + y * y + z * z,
            DISTANCE_MANHATTAN => x.abs() + y.abs() + z.abs(),
            _ => (x.abs() + y.abs() + z.abs()) + (x * x + y * y + z * z),
        }
    }

    #[inline(always)]
    fn measure2(&self, x: f64, y: f64) -> f64 {
        match self.distance {
            DISTANCE_EUCLIDEAN => x * x + y * y,
            DISTANCE_MANHATTAN => x.abs() + y.abs(),
            _ => (x.abs() + y.abs()) + (x * x + y * y),
        }
    }

    #[inline(always)]
    pub fn edge_result(&self, distance: f64, distance2: f64) -> f64 {
        match self.result {
            RETURN_DISTANCE2 => distance2 - 1.0,
            RETURN_DISTANCE2_ADD => distance2 + distance - 1.0,
            RETURN_DISTANCE2_SUB => distance2 - distance - 1.0,
            RETURN_DISTANCE2_MUL => distance2 * distance - 1.0,
            RETURN_DISTANCE2_DIV => distance / distance2 - 1.0,
            _ => 0.0,
        }
    }

    #[inline(always)]
    pub fn sample2(&self, x: f64, y: f64) -> f64 {
        if self.result == RETURN_CELL_VALUE || self.result == RETURN_DISTANCE {
            self.single2(x, y)
        } else {
            self.edge2(x, y)
        }
    }

    #[inline(always)]
    pub fn sample3(&self, x: f64, y: f64, z: f64) -> f64 {
        if self.result == RETURN_CELL_VALUE || self.result == RETURN_DISTANCE {
            self.single3(x, y, z)
        } else {
            self.edge3(x, y, z)
        }
    }

    #[inline(always)]
    fn single2(&self, x: f64, y: f64) -> f64 {
        let xr: i64 = fast_round(x);
        let yr: i64 = fast_round(y);
        let seed: u32 = self.seed as u32;
        let mut distance: f64 = 999999.0;
        let mut xc: i64 = 0;
        let mut yc: i64 = 0;
        for dx in -1i64..=1 {
            let xi: i64 = xr.wrapping_add(dx);
            let base_x: f64 = xi as f64 - x;
            for dy in -1i64..=1 {
                let yi: i64 = yr.wrapping_add(dy);
                let base_y: f64 = yi as f64 - y;
                let index: usize = (low_hash2(seed, xi as u32, yi as u32) & 255) as usize;
                let vec_x: f64 = base_x + CELL_2D_X[index];
                let vec_y: f64 = base_y + CELL_2D_Y[index];
                let candidate: f64 = self.measure2(vec_x, vec_y);
                let closer: bool = candidate < distance;
                distance = select(closer, candidate, distance);
                xc = if closer { xi } else { xc };
                yc = if closer { yi } else { yc };
            }
        }
        if self.result == RETURN_CELL_VALUE { val_coord2(0, xc, yc) } else { distance - 1.0 }
    }

    #[inline(always)]
    fn single3(&self, x: f64, y: f64, z: f64) -> f64 {
        let xr: i64 = fast_round(x);
        let yr: i64 = fast_round(y);
        let zr: i64 = fast_round(z);
        let seed: u32 = self.seed as u32;
        let mut distance: f64 = 999999.0;
        let mut xc: i64 = 0;
        let mut yc: i64 = 0;
        let mut zc: i64 = 0;
        for dx in -1i64..=1 {
            let xi: i64 = xr.wrapping_add(dx);
            let base_x: f64 = xi as f64 - x;
            for dy in -1i64..=1 {
                let yi: i64 = yr.wrapping_add(dy);
                let base_y: f64 = yi as f64 - y;
                for dz in -1i64..=1 {
                    let zi: i64 = zr.wrapping_add(dz);
                    let base_z: f64 = zi as f64 - z;
                    let index: usize = (low_hash3(seed, xi as u32, yi as u32, zi as u32) & 255) as usize;
                    let vec_x: f64 = base_x + CELL_3D_X[index];
                    let vec_y: f64 = base_y + CELL_3D_Y[index];
                    let vec_z: f64 = base_z + CELL_3D_Z[index];
                    let candidate: f64 = self.measure(vec_x, vec_y, vec_z);
                    let closer: bool = candidate < distance;
                    distance = select(closer, candidate, distance);
                    xc = if closer { xi } else { xc };
                    yc = if closer { yi } else { yc };
                    zc = if closer { zi } else { zc };
                }
            }
        }
        if self.result == RETURN_CELL_VALUE { val_coord3(0, xc, yc, zc) } else { distance - 1.0 }
    }

    #[inline(always)]
    fn edge2(&self, x: f64, y: f64) -> f64 {
        let xr: i64 = fast_round(x);
        let yr: i64 = fast_round(y);
        let seed: u32 = self.seed as u32;
        let mut distance: f64 = 999999.0;
        let mut distance2: f64 = 999999.0;
        for dx in -1i64..=1 {
            let xi: i64 = xr.wrapping_add(dx);
            let base_x: f64 = xi as f64 - x;
            for dy in -1i64..=1 {
                let yi: i64 = yr.wrapping_add(dy);
                let base_y: f64 = yi as f64 - y;
                let index: usize = (low_hash2(seed, xi as u32, yi as u32) & 255) as usize;
                let candidate: f64 = self.measure2(base_x + CELL_2D_X[index], base_y + CELL_2D_Y[index]);
                let closer: bool = candidate < distance;
                distance2 = select(closer, distance, select(candidate < distance2, candidate, distance2));
                distance = select(closer, candidate, distance);
            }
        }
        let boundary: f64 = 1.55 - java_max((x - xr as f64).abs(), (y - yr as f64).abs());
        if self.measure(boundary, 0.0, 0.0) < distance2 {
            return self.refine2(x, y, xr, yr, distance, distance2);
        }
        self.edge_result(distance, distance2)
    }

    #[inline(never)]
    pub fn refine2(&self, x: f64, y: f64, xr: i64, yr: i64, mut distance: f64, mut distance2: f64) -> f64 {
        let seed: u32 = self.seed as u32;
        for dx in -2i64..=2 {
            let xi: i64 = xr.wrapping_add(dx);
            let base_x: f64 = xi as f64 - x;
            let bound_x: f64 = java_max(0.0, base_x.abs() - 0.45);
            if self.measure(bound_x, 0.0, 0.0) >= distance2 {
                continue;
            }
            for dy in -2i64..=2 {
                if dx.abs() < 2 && dy.abs() < 2 {
                    continue;
                }
                let yi: i64 = yr.wrapping_add(dy);
                let base_y: f64 = yi as f64 - y;
                let bound_y: f64 = java_max(0.0, base_y.abs() - 0.45);
                if self.measure(bound_x, bound_y, 0.0) >= distance2 {
                    continue;
                }
                let index: usize = (low_hash2(seed, xi as u32, yi as u32) & 255) as usize;
                let candidate: f64 = self.measure(base_x + CELL_2D_X[index], base_y + CELL_2D_Y[index], 0.0);
                if candidate < distance {
                    distance2 = distance;
                    distance = candidate;
                } else if candidate < distance2 {
                    distance2 = candidate;
                }
            }
        }
        self.edge_result(distance, distance2)
    }

    #[inline(always)]
    fn edge3(&self, x: f64, y: f64, z: f64) -> f64 {
        let xr: i64 = fast_round(x);
        let yr: i64 = fast_round(y);
        let zr: i64 = fast_round(z);
        let seed: u32 = self.seed as u32;
        let mut distance: f64 = 999999.0;
        let mut distance2: f64 = 999999.0;
        for dx in -1i64..=1 {
            let xi: i64 = xr.wrapping_add(dx);
            let base_x: f64 = xi as f64 - x;
            for dy in -1i64..=1 {
                let yi: i64 = yr.wrapping_add(dy);
                let base_y: f64 = yi as f64 - y;
                for dz in -1i64..=1 {
                    let zi: i64 = zr.wrapping_add(dz);
                    let base_z: f64 = zi as f64 - z;
                    let index: usize = (low_hash3(seed, xi as u32, yi as u32, zi as u32) & 255) as usize;
                    let candidate: f64 = self.measure(
                        base_x + CELL_3D_X[index],
                        base_y + CELL_3D_Y[index],
                        base_z + CELL_3D_Z[index],
                    );
                    let closer: bool = candidate < distance;
                    distance2 = select(closer, distance, select(candidate < distance2, candidate, distance2));
                    distance = select(closer, candidate, distance);
                }
            }
        }
        let boundary: f64 = 1.55
            - java_max(
                (x - xr as f64).abs(),
                java_max((y - yr as f64).abs(), (z - zr as f64).abs()),
            );
        if self.measure(boundary, 0.0, 0.0) < distance2 {
            return self.refine3(x, y, z, xr, yr, zr, distance, distance2);
        }
        self.edge_result(distance, distance2)
    }

    #[inline(never)]
    #[allow(clippy::too_many_arguments)]
    pub fn refine3(&self, x: f64, y: f64, z: f64, xr: i64, yr: i64, zr: i64, mut distance: f64, mut distance2: f64) -> f64 {
        let seed: u32 = self.seed as u32;
        let radius: i64 = if self.distance == DISTANCE_MANHATTAN { 3 } else { 2 };
        for dx in -radius..=radius {
            let xi: i64 = xr.wrapping_add(dx);
            let base_x: f64 = xi as f64 - x;
            let bound_x: f64 = java_max(0.0, base_x.abs() - 0.45);
            if self.measure(bound_x, 0.0, 0.0) >= distance2 {
                continue;
            }
            for dy in -radius..=radius {
                let yi: i64 = yr.wrapping_add(dy);
                let base_y: f64 = yi as f64 - y;
                let bound_y: f64 = java_max(0.0, base_y.abs() - 0.45);
                if self.measure(bound_x, bound_y, 0.0) >= distance2 {
                    continue;
                }
                for dz in -radius..=radius {
                    if dx.abs() < 2 && dy.abs() < 2 && dz.abs() < 2 {
                        continue;
                    }
                    let zi: i64 = zr.wrapping_add(dz);
                    let base_z: f64 = zi as f64 - z;
                    let bound_z: f64 = java_max(0.0, base_z.abs() - 0.45);
                    if self.measure(bound_x, bound_y, bound_z) >= distance2 {
                        continue;
                    }
                    let index: usize = (low_hash3(seed, xi as u32, yi as u32, zi as u32) & 255) as usize;
                    let candidate: f64 = self.measure(
                        base_x + CELL_3D_X[index],
                        base_y + CELL_3D_Y[index],
                        base_z + CELL_3D_Z[index],
                    );
                    if candidate < distance {
                        distance2 = distance;
                        distance = candidate;
                    } else if candidate < distance2 {
                        distance2 = candidate;
                    }
                }
            }
        }
        self.edge_result(distance, distance2)
    }
}
