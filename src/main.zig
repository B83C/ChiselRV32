//! By convention, main.zig is where your main function lives in the case that
//! you are building an executable. If you are making a library, the convention
//! is to delete this file and start with root.zig instead.

export fn _start() callconv(.Naked) noreturn {
    asm volatile ("la sp, _sstack");
    asm volatile ("la s0, _sstack");

    asm volatile (
        \\ call %[func]
        :
        : [func] "i" (&main),
        : "ra"
    );
    // @call(std.builtin.CallModifier.auto, tt, .{});
    // syscon.* = 0x5555;
    while (true) {}
}

inline fn notOptimised() void {
    const a: u32 = 78;
    var b: u32 = 98;
    var c: u32 = 0;
    std.mem.doNotOptimizeAway(a);
    std.mem.doNotOptimizeAway(b);
    b = c + 1;
    // c = a + b * 2;
    std.mem.doNotOptimizeAway(c);
    c = a + b * 2;
}

noinline fn testProcedure(input: u32) u32 {
    var c: u32 = 3;
    std.mem.doNotOptimizeAway(c);
    c = c + input;
    return c * 3;
}

var t: u32 = 0;

noinline fn modifyGlobal(ptr: *u32) void {
    ptr.* = 1234;
}

pub fn main() !void {
    // Prints to stderr (it's a shortcut based on `std.io.getStdErr()`)
    modifyGlobal(&t);
    asm volatile ("csrrw x0, 0xF, %[out]"
        :
        : [out] "r" (t),
    );
    // while (true) {
    //     notOptimised();
    //     const output = testProcedure(t);
    //     asm volatile ("csrrw x0, 0xF, %[out]"
    //         :
    //         : [out] "r" (output),
    //     );
    //     asm volatile ("csrrwi x0, 0xF, 30");
    //     inline for (0..10) |_| {
    //         asm volatile ("nop");
    //     }
    //     t += 1;
    // }
}

// pub fn multiplicaton_test() !void {
//     const a, const b: u32 = 0xdeadbeef;
//     const c = a * b;
//     @
// }

const std = @import("std");

/// This imports the separate module containing `root.zig`. Take a look in `build.zig` for details.
const lib = @import("ChiselRV32_lib");
