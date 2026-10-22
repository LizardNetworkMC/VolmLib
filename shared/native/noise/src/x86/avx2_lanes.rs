use std::arch::x86_64::*;

use super::{Simd, BIAS, BIAS_HIGH_TABLE, BIAS_LOW_TABLE, COORDINATE_LIMIT, GRAD3_X_HALVES, GRAD3_Y_HALVES, GRAD3_Z_HALVES, LOW_WORD_MAGIC, NONE};
use crate::kernels::{GRAD2_X_BYTES, GRAD2_Y_BYTES};

const PLACE_WIDE: [u8; 32] = [
    NONE, NONE, NONE, NONE, NONE, NONE, 0, 0, NONE, NONE, NONE, NONE, NONE, NONE, 8, 8, NONE, NONE, NONE, NONE, NONE, NONE, 0, 0, NONE,
    NONE, NONE, NONE, NONE, NONE, 8, 8,
];

#[derive(Clone, Copy)]
pub(super) struct Avx2;

#[inline(always)]
fn wide_bytes(table: &[u8; 32]) -> __m256i {
    unsafe { _mm256_loadu_si256(table.as_ptr() as *const __m256i) }
}

#[inline(always)]
fn wide_table(table: &[u8; 16]) -> __m256i {
    unsafe { _mm256_broadcastsi128_si256(_mm_loadu_si128(table.as_ptr() as *const __m128i)) }
}

#[inline(always)]
fn wide_hash(seed: __m256i, base: __m256i) -> __m256i {
    unsafe {
        let base: __m256i = _mm256_xor_si256(seed, base);
        let cubed: __m256i = _mm256_mullo_epi32(_mm256_mullo_epi32(_mm256_mullo_epi32(base, base), base), _mm256_set1_epi32(60493));
        _mm256_xor_si256(_mm256_srli_epi32::<13>(cubed), cubed)
    }
}

#[inline(always)]
fn wide_indices(hash: __m256i, mask: i64) -> __m256i {
    unsafe {
        let doubled: __m256i = _mm256_slli_epi64::<1>(_mm256_and_si256(hash, _mm256_set1_epi64x(mask)));
        _mm256_shuffle_epi8(doubled, wide_bytes(&PLACE_WIDE))
    }
}

#[inline(always)]
fn wide_component3(halves: &([u8; 16], [u8; 16]), low: __m256i, high: __m256i) -> __m256d {
    unsafe {
        _mm256_castsi256_pd(_mm256_or_si256(
            _mm256_shuffle_epi8(wide_table(&halves.0), low),
            _mm256_shuffle_epi8(wide_table(&halves.1), high),
        ))
    }
}

impl Simd for Avx2 {
    type F = __m256d;
    type M = __m256d;
    type H = __m256i;

    #[inline(always)]
    fn load(values: &[f64], offset: usize) -> __m256d {
        unsafe { _mm256_loadu_pd(values.as_ptr().add(offset)) }
    }

    #[inline(always)]
    fn store(values: &mut [f64], offset: usize, value: __m256d) {
        unsafe { _mm256_storeu_pd(values.as_mut_ptr().add(offset), value) }
    }

    #[inline(always)]
    fn lanes(value: __m256d) -> [f64; 4] {
        let mut values: [f64; 4] = [0.0; 4];
        unsafe { _mm256_storeu_pd(values.as_mut_ptr(), value) };
        values
    }

    #[inline(always)]
    fn splat(value: f64) -> __m256d {
        unsafe { _mm256_set1_pd(value) }
    }

    #[inline(always)]
    fn add(left: __m256d, right: __m256d) -> __m256d {
        unsafe { _mm256_add_pd(left, right) }
    }

    #[inline(always)]
    fn sub(left: __m256d, right: __m256d) -> __m256d {
        unsafe { _mm256_sub_pd(left, right) }
    }

    #[inline(always)]
    fn mul(left: __m256d, right: __m256d) -> __m256d {
        unsafe { _mm256_mul_pd(left, right) }
    }

    #[inline(always)]
    fn div(left: __m256d, right: __m256d) -> __m256d {
        unsafe { _mm256_div_pd(left, right) }
    }

    #[inline(always)]
    fn absolute(value: __m256d) -> __m256d {
        unsafe { _mm256_andnot_pd(_mm256_set1_pd(-0.0), value) }
    }

    #[inline(always)]
    fn floor(value: __m256d) -> __m256d {
        unsafe { _mm256_add_pd(_mm256_floor_pd(value), _mm256_setzero_pd()) }
    }

    #[inline(always)]
    fn truncate(value: __m256d) -> __m256d {
        unsafe { _mm256_add_pd(_mm256_round_pd::<{ _MM_FROUND_TO_ZERO | _MM_FROUND_NO_EXC }>(value), _mm256_setzero_pd()) }
    }

    #[inline(always)]
    fn bounded(value: __m256d) -> bool {
        unsafe { _mm256_movemask_pd(_mm256_cmp_pd::<_CMP_LT_OQ>(Self::absolute(value), _mm256_set1_pd(COORDINATE_LIMIT))) == 0xf }
    }

    #[inline(always)]
    fn lt(left: __m256d, right: __m256d) -> __m256d {
        unsafe { _mm256_cmp_pd::<_CMP_LT_OQ>(left, right) }
    }

    #[inline(always)]
    fn gt(left: __m256d, right: __m256d) -> __m256d {
        unsafe { _mm256_cmp_pd::<_CMP_GT_OQ>(left, right) }
    }

    #[inline(always)]
    fn ge(left: __m256d, right: __m256d) -> __m256d {
        unsafe { _mm256_cmp_pd::<_CMP_GE_OQ>(left, right) }
    }

    #[inline(always)]
    fn ordered(value: __m256d) -> __m256d {
        unsafe { _mm256_cmp_pd::<_CMP_EQ_OQ>(value, value) }
    }

    #[inline(always)]
    fn and(left: __m256d, right: __m256d) -> __m256d {
        unsafe { _mm256_and_pd(left, right) }
    }

    #[inline(always)]
    fn or(left: __m256d, right: __m256d) -> __m256d {
        unsafe { _mm256_or_pd(left, right) }
    }

    #[inline(always)]
    fn not(mask: __m256d) -> __m256d {
        unsafe { _mm256_xor_pd(mask, _mm256_castsi256_pd(_mm256_set1_epi64x(-1))) }
    }

    #[inline(always)]
    fn choose(mask: __m256d, yes: __m256d, no: __m256d) -> __m256d {
        unsafe { _mm256_blendv_pd(no, yes, mask) }
    }

    #[inline(always)]
    fn flag(mask: __m256d) -> __m256d {
        unsafe { _mm256_and_pd(mask, _mm256_set1_pd(1.0)) }
    }

    #[inline(always)]
    fn mask_lanes(mask: __m256d) -> [bool; 4] {
        let bits: i32 = unsafe { _mm256_movemask_pd(mask) };
        [bits & 1 != 0, bits & 2 != 0, bits & 4 != 0, bits & 8 != 0]
    }

    #[inline(always)]
    fn low_word(value: __m256d) -> __m256i {
        unsafe { _mm256_castpd_si256(_mm256_add_pd(value, _mm256_set1_pd(LOW_WORD_MAGIC))) }
    }

    #[inline(always)]
    fn seed(seed: i64) -> __m256i {
        unsafe { _mm256_set1_epi32(seed as i32) }
    }

    #[inline(always)]
    fn plus_one(value: __m256i) -> __m256i {
        unsafe { _mm256_add_epi32(value, _mm256_set1_epi32(1)) }
    }

    #[inline(always)]
    fn plus_mask(value: __m256i, mask: __m256d) -> __m256i {
        unsafe { _mm256_sub_epi32(value, _mm256_castpd_si256(mask)) }
    }

    #[inline(always)]
    fn hash2(seed: __m256i, x: __m256i, y: __m256i) -> __m256i {
        unsafe {
            wide_hash(
                seed,
                _mm256_xor_si256(_mm256_mullo_epi32(x, _mm256_set1_epi32(1619)), _mm256_mullo_epi32(y, _mm256_set1_epi32(31337))),
            )
        }
    }

    #[inline(always)]
    fn hash3(seed: __m256i, x: __m256i, y: __m256i, z: __m256i) -> __m256i {
        unsafe {
            wide_hash(
                seed,
                _mm256_xor_si256(
                    _mm256_xor_si256(_mm256_mullo_epi32(x, _mm256_set1_epi32(1619)), _mm256_mullo_epi32(y, _mm256_set1_epi32(31337))),
                    _mm256_mullo_epi32(z, _mm256_set1_epi32(6971)),
                ),
            )
        }
    }

    #[inline(always)]
    fn dot2(hash: __m256i, x: __m256d, y: __m256d) -> __m256d {
        unsafe {
            let indices: __m256i = _mm256_add_epi8(wide_indices(hash, 7), wide_bytes(&BIAS));
            let gx: __m256d = _mm256_castsi256_pd(_mm256_shuffle_epi8(wide_table(&GRAD2_X_BYTES), indices));
            let gy: __m256d = _mm256_castsi256_pd(_mm256_shuffle_epi8(wide_table(&GRAD2_Y_BYTES), indices));
            Self::add(Self::mul(x, gx), Self::mul(y, gy))
        }
    }

    #[inline(always)]
    fn dot3(hash: __m256i, x: __m256d, y: __m256d, z: __m256d) -> __m256d {
        unsafe {
            let placed: __m256i = wide_indices(hash, 15);
            let low: __m256i = _mm256_add_epi8(placed, wide_bytes(&BIAS_LOW_TABLE));
            let high: __m256i = _mm256_add_epi8(placed, wide_bytes(&BIAS_HIGH_TABLE));
            let gx: __m256d = wide_component3(&GRAD3_X_HALVES, low, high);
            let gy: __m256d = wide_component3(&GRAD3_Y_HALVES, low, high);
            let gz: __m256d = wide_component3(&GRAD3_Z_HALVES, low, high);
            Self::add(Self::add(Self::mul(x, gx), Self::mul(y, gy)), Self::mul(z, gz))
        }
    }

    #[inline(always)]
    fn gather(table: &[f64; 256], hash: __m256i) -> __m256d {
        let mut words: [u64; 4] = [0; 4];
        unsafe { _mm256_storeu_si256(words.as_mut_ptr() as *mut __m256i, hash) };
        unsafe {
            _mm256_setr_pd(
                table[(words[0] & 255) as usize],
                table[(words[1] & 255) as usize],
                table[(words[2] & 255) as usize],
                table[(words[3] & 255) as usize],
            )
        }
    }
}
