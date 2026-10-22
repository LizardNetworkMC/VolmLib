mod batch;
mod billow;
mod ffi;
#[cfg(feature = "gpu")]
mod gpu;
#[cfg(feature = "gpu")]
mod gpu_program;
#[cfg(feature = "gpu")]
mod gpu_wgsl;
mod kernels;
#[cfg(target_arch = "aarch64")]
mod neon;
#[cfg(not(any(target_arch = "aarch64", target_arch = "x86_64")))]
mod portable;
mod program;
mod tables;
#[cfg(test)]
mod testing;
#[cfg(target_arch = "x86_64")]
mod x86;
