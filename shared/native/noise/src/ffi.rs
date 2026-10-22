use std::cell::RefCell;
use std::panic::{catch_unwind, AssertUnwindSafe};

use crate::program::{encoded_size, Header, Program, Scratch, VERSION};

thread_local! {
    static SCRATCH: RefCell<Scratch> = RefCell::new(Scratch::default());
}

fn with_program<F: FnOnce(&Program, &mut Scratch)>(program: *const u8, action: F) -> *const f64 {
    let result: Result<*const f64, _> = catch_unwind(AssertUnwindSafe(|| {
        let Some(view) = (unsafe { Program::view(program) }) else {
            return std::ptr::null();
        };
        SCRATCH.with(|cell| match cell.try_borrow_mut() {
            Ok(mut scratch) => {
                action(&view, &mut scratch);
                scratch.out.as_ptr()
            }
            Err(_) => std::ptr::null(),
        })
    }));
    result.unwrap_or(std::ptr::null())
}

fn count(value: i32) -> Option<usize> {
    if value < 0 { None } else { Some(value as usize) }
}

#[unsafe(no_mangle)]
pub extern "C" fn volmlib_noise_abi() -> i32 {
    VERSION as i32
}

#[unsafe(no_mangle)]
pub extern "C" fn volmlib_noise_validate(program: *const u8, length: i64) -> i32 {
    let result: Result<i32, _> = catch_unwind(|| {
        if program.is_null() || length < size_of::<Header>() as i64 || (program as usize) % 8 != 0 {
            return 0;
        }
        let header: &Header = unsafe { &*(program as *const Header) };
        match encoded_size(header) {
            Some(size) if size as i64 == length => {}
            _ => return 0,
        }
        match unsafe { Program::view(program) } {
            Some(view) if view.validate() => 1,
            _ => 0,
        }
    });
    result.unwrap_or(0)
}

#[unsafe(no_mangle)]
pub extern "C" fn volmlib_noise_input(capacity: i32) -> *mut f64 {
    let Some(capacity) = count(capacity) else {
        return std::ptr::null_mut();
    };
    let result: Result<*mut f64, _> = catch_unwind(|| {
        SCRATCH.with(|cell| match cell.try_borrow_mut() {
            Ok(mut scratch) => {
                if scratch.input.len() < capacity {
                    scratch.input.resize(capacity, 0.0);
                }
                scratch.input.as_mut_ptr()
            }
            Err(_) => std::ptr::null_mut(),
        })
    });
    result.unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn volmlib_noise_fill2d(program: *const u8, points: i32) -> *const f64 {
    let Some(points) = count(points) else {
        return std::ptr::null();
    };
    with_program(program, |view, scratch| {
        if scratch.input.len() < points * 2 {
            scratch.input.resize(points * 2, 0.0);
        }
        scratch.fill2d(view, points);
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn volmlib_noise_grid2d(program: *const u8, x0: f64, z0: f64, step: f64, width: i32, depth: i32) -> *const f64 {
    let (Some(width), Some(depth)) = (count(width), count(depth)) else {
        return std::ptr::null();
    };
    if width.checked_mul(depth).is_none() {
        return std::ptr::null();
    }
    with_program(program, |view, scratch| scratch.fill_grid2d(view, x0, z0, step, width, depth))
}

#[unsafe(no_mangle)]
pub extern "C" fn volmlib_noise_column3d(program: *const u8, x: f64, z: f64, y0: f64, step: f64, points: i32) -> *const f64 {
    let Some(points) = count(points) else {
        return std::ptr::null();
    };
    with_program(program, |view, scratch| scratch.fill_column3d(view, x, z, y0, step, points))
}

#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub extern "C" fn volmlib_noise_lattice3d(
    program: *const u8,
    x0: f64,
    y0: f64,
    z0: f64,
    step_x: f64,
    step_y: f64,
    step_z: f64,
    nx: i32,
    ny: i32,
    nz: i32,
) -> *const f64 {
    let (Some(nx), Some(ny), Some(nz)) = (count(nx), count(ny), count(nz)) else {
        return std::ptr::null();
    };
    if nx.checked_mul(ny).and_then(|value| value.checked_mul(nz)).is_none() {
        return std::ptr::null();
    }
    with_program(program, |view, scratch| {
        scratch.fill_lattice3d(view, [x0, y0, z0], [step_x, step_y, step_z], nx, ny, nz)
    })
}
