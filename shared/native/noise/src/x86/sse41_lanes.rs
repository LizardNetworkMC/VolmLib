use std::arch::x86_64::*;

use super::{Simd, BIAS, BIAS_HIGH_TABLE, BIAS_LOW_TABLE, COORDINATE_LIMIT, GRAD3_X_HALVES, GRAD3_Y_HALVES, GRAD3_Z_HALVES, LOW_WORD_MAGIC, NONE};
use crate::kernels::{GRAD2_X_BYTES, GRAD2_Y_BYTES};

const PLACE_LOW: [u8; 16] = [NONE, NONE, NONE, NONE, NONE, NONE, 0, 0, NONE, NONE, NONE, NONE, NONE, NONE, 4, 4];
const PLACE_HIGH: [u8; 16] = [NONE, NONE, NONE, NONE, NONE, NONE, 8, 8, NONE, NONE, NONE, NONE, NONE, NONE, 12, 12];

#[derive(Clone, Copy)]
pub(super) struct Sse41;

#[derive(Clone, Copy)]
pub(super) struct Pair {
    a: __m128d,
    b: __m128d,
}

#[inline(always)]
fn pair(a: __m128d, b: __m128d) -> Pair {
    Pair { a, b }
}

#[inline(always)]
fn narrow(pair: Pair) -> __m128i {
    unsafe { _mm_castps_si128(_mm_shuffle_ps::<0b10_00_10_00>(_mm_castpd_ps(pair.a), _mm_castpd_ps(pair.b))) }
}

#[inline(always)]
fn narrow_bytes(table: &[u8; 16]) -> __m128i {
    unsafe { _mm_loadu_si128(table.as_ptr() as *const __m128i) }
}

#[inline(always)]
fn narrow_hash(seed: __m128i, base: __m128i) -> __m128i {
    unsafe {
        let base: __m128i = _mm_xor_si128(seed, base);
        let cubed: __m128i = _mm_mullo_epi32(_mm_mullo_epi32(_mm_mullo_epi32(base, base), base), _mm_set1_epi32(60493));
        _mm_xor_si128(_mm_srli_epi32::<13>(cubed), cubed)
    }
}

#[inline(always)]
fn narrow_indices(hash: __m128i, mask: i32) -> (__m128i, __m128i) {
    unsafe {
        let doubled: __m128i = _mm_slli_epi32::<1>(_mm_and_si128(hash, _mm_set1_epi32(mask)));
        (_mm_shuffle_epi8(doubled, narrow_bytes(&PLACE_LOW)), _mm_shuffle_epi8(doubled, narrow_bytes(&PLACE_HIGH)))
    }
}

#[inline(always)]
fn narrow_bias(placed: __m128i, bias: &[u8; 32]) -> __m128i {
    unsafe { _mm_add_epi8(placed, _mm_loadu_si128(bias.as_ptr() as *const __m128i)) }
}

#[inline(always)]
fn narrow_component3(halves: &([u8; 16], [u8; 16]), low: __m128i, high: __m128i) -> __m128d {
    unsafe {
        _mm_castsi128_pd(_mm_or_si128(
            _mm_shuffle_epi8(narrow_bytes(&halves.0), low),
            _mm_shuffle_epi8(narrow_bytes(&halves.1), high),
        ))
    }
}

impl Simd for Sse41 {
    type F = Pair;
    type M = Pair;
    type H = __m128i;

    #[inline(always)]
    fn load(values: &[f64], offset: usize) -> Pair {
        unsafe { pair(_mm_loadu_pd(values.as_ptr().add(offset)), _mm_loadu_pd(values.as_ptr().add(offset + 2))) }
    }

    #[inline(always)]
    fn store(values: &mut [f64], offset: usize, value: Pair) {
        unsafe {
            _mm_storeu_pd(values.as_mut_ptr().add(offset), value.a);
            _mm_storeu_pd(values.as_mut_ptr().add(offset + 2), value.b);
        }
    }

    #[inline(always)]
    fn lanes(value: Pair) -> [f64; 4] {
        let mut values: [f64; 4] = [0.0; 4];
        Self::store(&mut values, 0, value);
        values
    }

    #[inline(always)]
    fn splat(value: f64) -> Pair {
        unsafe { pair(_mm_set1_pd(value), _mm_set1_pd(value)) }
    }

    #[inline(always)]
    fn add(left: Pair, right: Pair) -> Pair {
        unsafe { pair(_mm_add_pd(left.a, right.a), _mm_add_pd(left.b, right.b)) }
    }

    #[inline(always)]
    fn sub(left: Pair, right: Pair) -> Pair {
        unsafe { pair(_mm_sub_pd(left.a, right.a), _mm_sub_pd(left.b, right.b)) }
    }

    #[inline(always)]
    fn mul(left: Pair, right: Pair) -> Pair {
        unsafe { pair(_mm_mul_pd(left.a, right.a), _mm_mul_pd(left.b, right.b)) }
    }

    #[inline(always)]
    fn div(left: Pair, right: Pair) -> Pair {
        unsafe { pair(_mm_div_pd(left.a, right.a), _mm_div_pd(left.b, right.b)) }
    }

    #[inline(always)]
    fn absolute(value: Pair) -> Pair {
        unsafe {
            let sign: __m128d = _mm_set1_pd(-0.0);
            pair(_mm_andnot_pd(sign, value.a), _mm_andnot_pd(sign, value.b))
        }
    }

    #[inline(always)]
    fn floor(value: Pair) -> Pair {
        unsafe {
            let zero: __m128d = _mm_setzero_pd();
            pair(_mm_add_pd(_mm_floor_pd(value.a), zero), _mm_add_pd(_mm_floor_pd(value.b), zero))
        }
    }

    #[inline(always)]
    fn truncate(value: Pair) -> Pair {
        unsafe {
            let zero: __m128d = _mm_setzero_pd();
            pair(
                _mm_add_pd(_mm_round_pd::<{ _MM_FROUND_TO_ZERO | _MM_FROUND_NO_EXC }>(value.a), zero),
                _mm_add_pd(_mm_round_pd::<{ _MM_FROUND_TO_ZERO | _MM_FROUND_NO_EXC }>(value.b), zero),
            )
        }
    }

    #[inline(always)]
    fn bounded(value: Pair) -> bool {
        let below: Pair = Self::lt(Self::absolute(value), Self::splat(COORDINATE_LIMIT));
        unsafe { (_mm_movemask_pd(below.a) & _mm_movemask_pd(below.b)) == 3 }
    }

    #[inline(always)]
    fn lt(left: Pair, right: Pair) -> Pair {
        unsafe { pair(_mm_cmplt_pd(left.a, right.a), _mm_cmplt_pd(left.b, right.b)) }
    }

    #[inline(always)]
    fn gt(left: Pair, right: Pair) -> Pair {
        unsafe { pair(_mm_cmpgt_pd(left.a, right.a), _mm_cmpgt_pd(left.b, right.b)) }
    }

    #[inline(always)]
    fn ge(left: Pair, right: Pair) -> Pair {
        unsafe { pair(_mm_cmpge_pd(left.a, right.a), _mm_cmpge_pd(left.b, right.b)) }
    }

    #[inline(always)]
    fn ordered(value: Pair) -> Pair {
        unsafe { pair(_mm_cmpeq_pd(value.a, value.a), _mm_cmpeq_pd(value.b, value.b)) }
    }

    #[inline(always)]
    fn and(left: Pair, right: Pair) -> Pair {
        unsafe { pair(_mm_and_pd(left.a, right.a), _mm_and_pd(left.b, right.b)) }
    }

    #[inline(always)]
    fn or(left: Pair, right: Pair) -> Pair {
        unsafe { pair(_mm_or_pd(left.a, right.a), _mm_or_pd(left.b, right.b)) }
    }

    #[inline(always)]
    fn not(mask: Pair) -> Pair {
        unsafe {
            let ones: __m128d = _mm_castsi128_pd(_mm_set1_epi32(-1));
            pair(_mm_xor_pd(mask.a, ones), _mm_xor_pd(mask.b, ones))
        }
    }

    #[inline(always)]
    fn choose(mask: Pair, yes: Pair, no: Pair) -> Pair {
        unsafe { pair(_mm_blendv_pd(no.a, yes.a, mask.a), _mm_blendv_pd(no.b, yes.b, mask.b)) }
    }

    #[inline(always)]
    fn flag(mask: Pair) -> Pair {
        unsafe {
            let one: __m128d = _mm_set1_pd(1.0);
            pair(_mm_and_pd(mask.a, one), _mm_and_pd(mask.b, one))
        }
    }

    #[inline(always)]
    fn mask_lanes(mask: Pair) -> [bool; 4] {
        let low: i32 = unsafe { _mm_movemask_pd(mask.a) };
        let high: i32 = unsafe { _mm_movemask_pd(mask.b) };
        [low & 1 != 0, low & 2 != 0, high & 1 != 0, high & 2 != 0]
    }

    #[inline(always)]
    fn low_word(value: Pair) -> __m128i {
        unsafe {
            let magic: __m128d = _mm_set1_pd(LOW_WORD_MAGIC);
            narrow(pair(_mm_add_pd(value.a, magic), _mm_add_pd(value.b, magic)))
        }
    }

    #[inline(always)]
    fn seed(seed: i64) -> __m128i {
        unsafe { _mm_set1_epi32(seed as i32) }
    }

    #[inline(always)]
    fn plus_one(value: __m128i) -> __m128i {
        unsafe { _mm_add_epi32(value, _mm_set1_epi32(1)) }
    }

    #[inline(always)]
    fn plus_mask(value: __m128i, mask: Pair) -> __m128i {
        unsafe { _mm_sub_epi32(value, narrow(mask)) }
    }

    #[inline(always)]
    fn hash2(seed: __m128i, x: __m128i, y: __m128i) -> __m128i {
        unsafe {
            narrow_hash(seed, _mm_xor_si128(_mm_mullo_epi32(x, _mm_set1_epi32(1619)), _mm_mullo_epi32(y, _mm_set1_epi32(31337))))
        }
    }

    #[inline(always)]
    fn hash3(seed: __m128i, x: __m128i, y: __m128i, z: __m128i) -> __m128i {
        unsafe {
            narrow_hash(
                seed,
                _mm_xor_si128(
                    _mm_xor_si128(_mm_mullo_epi32(x, _mm_set1_epi32(1619)), _mm_mullo_epi32(y, _mm_set1_epi32(31337))),
                    _mm_mullo_epi32(z, _mm_set1_epi32(6971)),
                ),
            )
        }
    }

    #[inline(always)]
    fn dot2(hash: __m128i, x: Pair, y: Pair) -> Pair {
        unsafe {
            let (low, high): (__m128i, __m128i) = narrow_indices(hash, 7);
            let low: __m128i = narrow_bias(low, &BIAS);
            let high: __m128i = narrow_bias(high, &BIAS);
            let tx: __m128i = narrow_bytes(&GRAD2_X_BYTES);
            let ty: __m128i = narrow_bytes(&GRAD2_Y_BYTES);
            let gx: Pair = pair(_mm_castsi128_pd(_mm_shuffle_epi8(tx, low)), _mm_castsi128_pd(_mm_shuffle_epi8(tx, high)));
            let gy: Pair = pair(_mm_castsi128_pd(_mm_shuffle_epi8(ty, low)), _mm_castsi128_pd(_mm_shuffle_epi8(ty, high)));
            Self::add(Self::mul(x, gx), Self::mul(y, gy))
        }
    }

    #[inline(always)]
    fn dot3(hash: __m128i, x: Pair, y: Pair, z: Pair) -> Pair {
        let (low, high): (__m128i, __m128i) = narrow_indices(hash, 15);
        let low_a: __m128i = narrow_bias(low, &BIAS_LOW_TABLE);
        let high_a: __m128i = narrow_bias(low, &BIAS_HIGH_TABLE);
        let low_b: __m128i = narrow_bias(high, &BIAS_LOW_TABLE);
        let high_b: __m128i = narrow_bias(high, &BIAS_HIGH_TABLE);
        let gx: Pair = pair(narrow_component3(&GRAD3_X_HALVES, low_a, high_a), narrow_component3(&GRAD3_X_HALVES, low_b, high_b));
        let gy: Pair = pair(narrow_component3(&GRAD3_Y_HALVES, low_a, high_a), narrow_component3(&GRAD3_Y_HALVES, low_b, high_b));
        let gz: Pair = pair(narrow_component3(&GRAD3_Z_HALVES, low_a, high_a), narrow_component3(&GRAD3_Z_HALVES, low_b, high_b));
        Self::add(Self::add(Self::mul(x, gx), Self::mul(y, gy)), Self::mul(z, gz))
    }

    #[inline(always)]
    fn gather(table: &[f64; 256], hash: __m128i) -> Pair {
        let mut words: [u32; 4] = [0; 4];
        unsafe { _mm_storeu_si128(words.as_mut_ptr() as *mut __m128i, hash) };
        unsafe {
            pair(
                _mm_setr_pd(table[(words[0] & 255) as usize], table[(words[1] & 255) as usize]),
                _mm_setr_pd(table[(words[2] & 255) as usize], table[(words[3] & 255) as usize]),
            )
        }
    }
}
