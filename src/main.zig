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

noinline fn writeCSR(addr: u12, val: u32) void {
    asm volatile ("csrrw x0, %[addr], %[out]"
        :
        : [out] "r" (val),
          [addr] "i" (addr),
    );
}

noinline fn readCSR(addr: u12) u32 {
    return asm volatile ("csrrsi %[out], %[addr], 0"
        : [out] "=r" (-> u32),
        : [addr] "r" (addr),
    );
}
noinline fn print(str: [*:0]const u8) void {
    asm volatile ("csrrw x0, 0xA, %[out]"
        :
        : [out] "r" (str),
    );
}

const string_test = "Hello world!";

fn test_sl() void {
    var mem: [16]u8 = undefined;
    @memset(mem[0..], 0);

    const byte_val: u8 = 0xAB;
    const half_val: u16 = 0xCDEF;
    const word_val: u32 = 0x12345678;

    const byte_off = 0;
    const half_off = 4;
    const word_off = 8;

    // Store byte
    asm volatile (
        \\ sb %[val], 0(%[base])
        :
        : [val] "r" (byte_val),
          [base] "r" (&mem[byte_off]),
    );
    // Juxtapose additional store halfword
    asm volatile (
        \\ sh %[val], 0(%[base])
        :
        : [val] "r" (half_val),
          [base] "r" (&mem[half_off]),
    );

    // Store halfword
    asm volatile (
        \\ sh %[val], 0(%[base])
        :
        : [val] "r" (half_val),
          [base] "r" (&mem[half_off]),
    );
    // Juxtapose additional store word
    asm volatile (
        \\ sw %[val], 0(%[base])
        :
        : [val] "r" (word_val),
          [base] "r" (&mem[word_off]),
    );

    // Store word
    asm volatile (
        \\ sw %[val], 0(%[base])
        :
        : [val] "r" (word_val),
          [base] "r" (&mem[word_off]),
    );

    // Load back
    var lb: u32 = undefined;
    asm volatile (
        \\ lb %[res], 0(%[base])
        : [res] "=r" (lb),
        : [base] "r" (&mem[byte_off]),
    );

    var lh: u32 = undefined;
    asm volatile (
        \\ lh %[res], 0(%[base])
        : [res] "=r" (lh),
        : [base] "r" (&mem[half_off]),
    );

    var lw: u32 = undefined;
    asm volatile (
        \\ lw %[res], 0(%[base])
        : [res] "=r" (lw),
        : [base] "r" (&mem[word_off]),
    );

    // Assertions (with proper extension)
    assert_eq(lb, @as(u32, @bitCast(@as(i32, (@as(i8, @bitCast(byte_val)))))), "huh");
    assert_eq(lh, @as(u32, @bitCast(@as(i32, (@as(i16, @bitCast(half_val)))))), "huh");
    assert_eq(lw, word_val, "huh");
}

const t: u32 = 0xdead;
const str_test = "hy there";
pub fn main() !void {
    // Prints to stderr (it's a shortcut based on `std.io.getStdErr()`)
    // modifyGlobal(&t);
    // test_sl();
    var buf: [50:0]u8 = undefined;
    _ = std.fmt.bufPrintZ(&buf, "hell {x} {d} {s} {d} test \n", .{ t, t, str_test, t }) catch |err| {
        if (err == error.NoSpaceLeft) {
            print("No space left\n");
        } else {
            print("Other error\n");
        }
    };
    print(&buf);
    // load_store_tests();
    // branch_test();
    // print("hello there\n");
    // inline for (0..5) |_| {
    //     t += 1234;
    //     y += 4321;
    //     writeCSR(t);
    //     writeCSR(y);
    // }
    // rv32im_alu_tests();
    // rv32im_alu_tests_with_verification();
    // print("Now performing branch tests \n");
    // branch_test();
    // print("A value of 63 should be expected\n");
    // alu_test();
    // alu_test();
    // testAlu();
    // inline for (0..10) |_| {
    //     asm volatile ("nop");
    // }
    print("terminating");
}

// pub fn multiplicaton_test() !void {
//     const a, const b: u32 = 0xdeadbeef;
//     const c = a * b;
//     @
// }

const std = @import("std");

/// This imports the separate module containing `root.zig`. Take a look in `build.zig` for details.
const lib = @import("ChiselRV32_lib");

// comptime {
//     asm (
//         \\ .section .text
//         \\ .global _start
//         \\
//         \\ _start:
//         \\ li x5, 0xAAAAAAA5        # Set x5 to a known value
//         \\ li x6, 0x55555556        # Set x6 to another value
//         \\ li x7, 0                 # Clear x7
//         \\
//         \\ jal ra, jump_target      # Jump and save return address to x1 (ra)
//         \\
//         \\ # Returned here
//         \\ # x5 should still be 0xAAAAAAA5 if AMT/RMT handled properly
//         \\ li t0, 0xAAAAAAA5
//         \\ bne x5, t0, fail
//         \\
//         \\ li t0, 1
//         \\ csrw 0xF, t0           # Indicate PASS
//         \\ j done
//         \\
//         \\ jump_target:
//         \\ # Simulate clobbering registers
//         \\ li x5, 0x11111111
//         \\ li x6, 0x22222222
//         \\ # Return to caller
//         \\ jalr x0, ra, 0           # jalr x0, x1, 0
//         \\
//         \\ fail:
//         \\ li t0, 0
//         \\ csrw 0xF, t0           # Indicate FAIL
//         \\
//         \\ done:
//         \\ j done
//     );
// }

fn branch_test() void {
    const bitmap: u32 = asm volatile (
        \\ // beq test → t0,t1 → flag in t2
        \\ li t0, 5
        \\ li t1, 5
        \\ beq t0, t1, 2f
        \\ li t2, 0
        \\ j 3f
        \\ 2: li t2, 1
        \\ 3:
        \\ // bne test → t3,t4 → flag in t5
        \\ li t3, 5
        \\ li t4, 6
        \\ bne t3, t4, 6f
        \\ li t5, 0
        \\ j 7f
        \\ 6: li t5, 1
        \\ 7:
        \\ // blt test → t6 (signed), s0 → flag in s1
        \\ li t6, -1
        \\ li s0, 1
        \\ blt t6, s0, 10f
        \\ li s1, 0
        \\ j 11f
        \\ 10: li s1, 1
        \\ 11:
        \\ // bge test → a0,a1 → flag in a2
        \\ li a0, 2
        \\ li a1, 1
        \\ bge a0, a1, 14f
        \\ li a2, 0
        \\ j 15f
        \\ 14: li a2, 1
        \\ 15:
        \\ // bltu test → a3,a4 → flag in a5
        \\ li a3, 1
        \\ li a4, -1
        \\ bltu a3, a4, 18f
        \\ li a5, 0
        \\ j 19f
        \\ 18: li a5, 1
        \\ 19:
        \\ // bgeu test → a6,a7 → flag in t0
        \\ li a6, -1
        \\ li a7, 1
        \\ bgeu a6, a7, 22f
        \\ li t0, 0
        \\ j 23f
        \\ 22: li t0, 1
        \\ 23:
        \\ // Combine 6-bit result: bit0=beq, bit1=bne, bit2=blt, bit3=bge, bit4=bltu, bit5=bgeu
        \\ slli t2, t2, 0
        \\ slli t5, t5, 1
        \\ or t2, t2, t5
        \\ slli s1, s1, 2
        \\ or t2, t2, s1
        \\ slli a2, a2, 3
        \\ or t2, t2, a2
        \\ slli a5, a5, 4
        \\ or t2, t2, a5
        \\ slli t0, t0, 5
        \\ or t2, t2, t0
        \\ mv %[out], t2
        : [out] "=r" (-> u32),
        :
        : "t0", "t1", "t2", "t3", "t4", "t5", "t6", "s0", "s1", "a0", "a1", "a2", "a3", "a4", "a5", "a6", "a7"
    );
    writeCSR(0xF, bitmap);
}

pub fn rv32im_alu_tests_with_verification() void {
    print("Starting RV32IM ALU Tests with Verification...\n");

    const operand1: u32 = 0x12345678;
    const operand2: u32 = 0x87654321;

    var test_counter: u32 = 0;
    var instruction_result: u32 = 0;
    var pass_count: u32 = 0;

    // Test ADD with expected result
    test_counter += 1;
    print("Verified Test 1: ADD instruction\n");
    asm volatile (
        \\add %[result], %[op1], %[op2]
        : [result] "=r" (instruction_result),
        : [op1] "r" (operand1),
          [op2] "r" (operand2),
    );
    const expected_add = operand1 +% operand2; // Wrapping add for overflow
    const add_pass = instruction_result == expected_add;
    if (add_pass) {
        pass_count += 1;
        print("ADD test PASSED\n");
    } else {
        print("ADD test FAILED\n");
    }
    const add_result = create_test_result(test_counter, instruction_result, expected_add);
    asm volatile (
        \\csrrw zero, 0xF, %[value]
        :
        : [value] "r" (add_result),
        : "memory"
    );

    // Test SUB with expected result
    test_counter += 1;
    print("Verified Test 2: SUB instruction\n");
    asm volatile (
        \\sub %[result], %[op1], %[op2]
        : [result] "=r" (instruction_result),
        : [op1] "r" (operand1),
          [op2] "r" (operand2),
    );
    const expected_sub = operand1 -% operand2; // Wrapping sub for underflow
    const sub_pass = instruction_result == expected_sub;
    if (sub_pass) {
        pass_count += 1;
        print("SUB test PASSED\n");
    } else {
        print("SUB test FAILED\n");
    }
    const sub_result = create_test_result(test_counter, instruction_result, expected_sub);
    asm volatile (
        \\csrrw zero, 0xF, %[value]
        :
        : [value] "r" (sub_result),
        : "memory"
    );

    // Final verification summary
    print("Verification complete!\n");
    const verification_summary = (pass_count << 24) | (test_counter << 16) | 0xBEEF;
    asm volatile (
        \\csrrw zero, 0xF, %[summary]
        :
        : [summary] "r" (verification_summary),
        : "memory"
    );
    print("Verification summary written to CSR 0xF\n");
}

inline fn create_test_result(test_num: u32, instruction_result: u32, expected: u32) u32 {
    const pass: u32 = if (instruction_result == expected) 1 else 0;
    return (pass << 31) | (test_num << 16) | (instruction_result & 0xFFFF);
}

fn load_store_tests() void {
    var mem: [16]u8 = undefined;

    // Test cases: value, offset, instruction name
    inline for (.{
        .{ "Load/Store Word\n", 0x12345678, 0, .sw, .lw },
        .{ "Load/Store Halfword\n", -0x1234, 2, .sh, .lh },
        .{ "Load/Store Byte\n", 0x7F, 4, .sb, .lb },
        .{ "Unsigned Halfword\n", 0xABCD, 6, .sh, .lhu },
        .{ "Unsigned Byte\n", 0xFF, 8, .sb, .lbu },
    }) |tests| {
        const label = tests[0];
        const value: isize = tests[1];
        const offset = tests[2];
        const store_instr = tests[3];
        const load_instr = tests[4];

        print(label);

        // Clear memory
        @memset(&mem, 0);

        // Perform store
        asm volatile (@tagName(store_instr) ++
                \\ %[val], 0(%[base])
            :
            : [val] "r" (value),
              [base] "r" (&mem[offset]),
        );

        // Insert extra store right after to check juxtaposition
        asm volatile ("sw %[zero], 0(%[base])"
            :
            : [zero] "r" (0),
              [base] "r" (&mem[12]),
        );

        // Perform load
        var result: u32 = undefined;
        asm volatile (@tagName(load_instr) ++
                \\ %[out], 0(%[base])
            : [out] "=r" (result),
            : [base] "r" (&mem[offset]),
        );

        // Expected cast (manual to avoid UB)
        const expected: u32 = switch (load_instr) {
            .lw => value,
            .lh => @bitCast(@as(i32, @as(i16, value))),
            .lhu => @as(u32, @as(u16, value)),
            .lb => @bitCast(@as(i32, @as(i8, value))),
            .lbu => @as(u32, @as(u8, value)),
            else => @compileError("Unsupported instruction"),
        };

        assert_eq(result, expected, label);
    }
}

fn rv32im_alu_tests() void {
    print("Starting RV32IM ALU Tests\n");

    // Test operands
    const operand1: i32 = 0x1234;
    // const operand2: i32 = -0x8765;
    // const operand1: u32 = 0x12345678;
    // const operand2: u32 = 0x87654321;
    // const shift_amount: u32 = 15;
    // const test_constant: u32 = 1;

    var test_counter: u32 = 0;
    var instruction_result: u32 = 0;

    // =============================================================================
    // RV32I Base Integer Instructions - Arithmetic Tests
    // =============================================================================

    // print("Testing RV32I Arithmetic Instructions...\n");

    // // Test 1: ADD
    // test_counter += 1;
    // print("Test 1: ADD instruction\n");
    // asm volatile (
    //     \\add %[result], %[op1], %[op2]
    //     : [result] "=r" (instruction_result),
    //     : [op1] "r" (operand1),
    //       [op2] "r" (operand2),
    // );
    // write_test_result(test_counter, instruction_result);
    // print("ADD result written to CSR\n");

    // // Test 2: SUB
    // test_counter += 1;
    // print("Test 2: SUB instruction\n");
    // asm volatile (
    //     \\sub %[result], %[op1], %[op2]
    //     : [result] "=r" (instruction_result),
    //     : [op1] "r" (operand1),
    //       [op2] "r" (operand2),
    // );
    // write_test_result(test_counter, instruction_result);
    // print("SUB result written to CSR\n");

    // // Test 3: ADDI
    // test_counter += 1;
    // print("Test 3: ADDI instruction\n");
    // asm volatile (
    //     \\addi %[result], %[op1], 0x123
    //     : [result] "=r" (instruction_result),
    //     : [op1] "r" (operand1),
    // );
    // write_test_result(test_counter, instruction_result);
    // print("ADDI result written to CSR\n");

    // =============================================================================
    // RV32I Base Integer Instructions - Logical Tests
    // =============================================================================

    // print("Testing RV32I Logical Instructions...\n");

    // // Test 4: AND
    // test_counter += 1;
    // print("Test 4: AND instruction\n");
    // asm volatile (
    //     \\and %[result], %[op1], %[op2]
    //     : [result] "=r" (instruction_result),
    //     : [op1] "r" (operand1),
    //       [op2] "r" (operand2),
    // );
    // write_test_result(test_counter, instruction_result);
    // print("AND result written to CSR\n");

    // // Test 5: OR
    // test_counter += 1;
    // print("Test 5: OR instruction\n");
    // asm volatile (
    //     \\or %[result], %[op1], %[op2]
    //     : [result] "=r" (instruction_result),
    //     : [op1] "r" (operand1),
    //       [op2] "r" (operand2),
    // );
    // write_test_result(test_counter, instruction_result);
    // print("OR result written to CSR\n");

    // // Test 6: XOR
    // test_counter += 1;
    // print("Test 6: XOR instruction\n");
    // asm volatile (
    //     \\xor %[result], %[op1], %[op2]
    //     : [result] "=r" (instruction_result),
    //     : [op1] "r" (operand1),
    //       [op2] "r" (operand2),
    // );
    // write_test_result(test_counter, instruction_result);
    // print("XOR result written to CSR\n");

    // // Test 7: ANDI
    // test_counter += 1;
    // print("Test 7: ANDI instruction\n");
    // asm volatile (
    //     \\andi %[result], %[op1], 0xFF
    //     : [result] "=r" (instruction_result),
    //     : [op1] "r" (operand1),
    // );
    // write_test_result(test_counter, instruction_result);
    // print("ANDI result written to CSR\n");

    // // Test 8: ORI
    // test_counter += 1;
    // print("Test 8: ORI instruction\n");
    // asm volatile (
    //     \\ori %[result], %[op1], 0xFF
    //     : [result] "=r" (instruction_result),
    //     : [op1] "r" (operand1),
    // );
    // write_test_result(test_counter, instruction_result);
    // print("ORI result written to CSR\n");

    // // Test 9: XORI
    // test_counter += 1;
    // print("Test 9: XORI instruction\n");
    // asm volatile (
    //     \\xori %[result], %[op1], 0xFF
    //     : [result] "=r" (instruction_result),
    //     : [op1] "r" (operand1),
    // );
    // write_test_result(test_counter, instruction_result);
    // print("XORI result written to CSR\n");

    // =============================================================================
    // RV32I Base Integer Instructions - Shift Tests
    // =============================================================================

    // print("Testing RV32I Shift Instructions...\n");

    // // Test 10: SLL
    // test_counter += 1;
    // print("Test 10: SLL instruction\n");
    // asm volatile (
    //     \\sll %[result], %[op1], %[shift]
    //     : [result] "=r" (instruction_result),
    //     : [op1] "r" (operand1),
    //       [shift] "r" (shift_amount),
    // );
    // write_test_result(test_counter, instruction_result);
    // print("SLL result written to CSR\n");

    // // Test 11: SRL
    // test_counter += 1;
    // print("Test 11: SRL instruction\n");
    // asm volatile (
    //     \\srl %[result], %[op1], %[shift]
    //     : [result] "=r" (instruction_result),
    //     : [op1] "r" (operand1),
    //       [shift] "r" (shift_amount),
    // );
    // write_test_result(test_counter, instruction_result);
    // print("SRL result written to CSR\n");

    // // Test 12: SRA
    // test_counter += 1;
    // print("Test 12: SRA instruction\n");
    // asm volatile (
    //     \\sra %[result], %[op1], %[shift]
    //     : [result] "=r" (instruction_result),
    //     : [op1] "r" (operand1),
    //       [shift] "r" (shift_amount),
    // );
    // write_test_result(test_counter, instruction_result);
    // print("SRA result written to CSR\n");

    // // Test 13: SLLI
    // test_counter += 1;
    // print("Test 13: SLLI instruction\n");
    // asm volatile (
    //     \\slli %[result], %[op1], 8
    //     : [result] "=r" (instruction_result),
    //     : [op1] "r" (operand1),
    // );
    // write_test_result(test_counter, instruction_result);
    // print("SLLI result written to CSR\n");

    // // Test 14: SRLI
    // test_counter += 1;
    // print("Test 14: SRLI instruction\n");
    // asm volatile (
    //     \\srli %[result], %[op1], 8
    //     : [result] "=r" (instruction_result),
    //     : [op1] "r" (operand1),
    // );
    // write_test_result(test_counter, instruction_result);
    // print("SRLI result written to CSR\n");

    // // Test 15: SRAI
    // test_counter += 1;
    // print("Test 15: SRAI instruction\n");
    // asm volatile (
    //     \\srai %[result], %[op2], 8
    //     : [result] "=r" (instruction_result),
    //     : [op2] "r" (operand2),
    // );
    // write_test_result(test_counter, instruction_result);
    // print("SRAI result written to CSR\n");

    // =============================================================================
    // RV32I Base Integer Instructions - Comparison Tests
    // =============================================================================

    // print("Testing RV32I Comparison Instructions...\n");

    // // Test 16: SLT
    // test_counter += 1;
    // print("Test 16: SLT instruction\n");
    // asm volatile (
    //     \\slt %[result], %[op1], %[op2]
    //     : [result] "=r" (instruction_result),
    //     : [op1] "r" (operand1),
    //       [op2] "r" (operand2),
    // );
    // write_test_result(test_counter, instruction_result);
    // print("SLT result written to CSR\n");

    // // Test 17: SLTU
    // test_counter += 1;
    // print("Test 17: SLTU instruction\n");
    // asm volatile (
    //     \\sltu %[result], %[op1], %[op2]
    //     : [result] "=r" (instruction_result),
    //     : [op1] "r" (operand1),
    //       [op2] "r" (operand2),
    // );
    // write_test_result(test_counter, instruction_result);
    // print("SLTU result written to CSR\n");

    // // Test 18: SLTI
    // test_counter += 1;
    // print("Test 18: SLTI instruction\n");
    // asm volatile (
    //     \\slti %[result], %[op1], -1
    //     : [result] "=r" (instruction_result),
    //     : [op1] "r" (operand1),
    // );
    // write_test_result(test_counter, instruction_result);
    // print("SLTI result written to CSR\n");

    // // Test 19: SLTIU
    // test_counter += 1;
    // print("Test 19: SLTIU instruction\n");
    // asm volatile (
    //     \\sltiu %[result], %[op1], 0x80
    //     : [result] "=r" (instruction_result),
    //     : [op1] "r" (operand1),
    // );
    // write_test_result(test_counter, instruction_result);
    // print("SLTIU result written to CSR\n");

    inline for (.{ .{ 0x1234, -0x8765, "Mul test1\n" }, .{ -0x1234, 0x8765, "Mul test2\n" } }) |multipliers| {
        const m: i32 = multipliers[0];
        const mr: i32 = multipliers[1];

        const mu: u32 = @bitCast(m);
        const mru: u32 = @bitCast(mr);

        const mhu: u32 = (@as(u64, mu) * @as(u64, mru)) >> 32;
        const mhsu: i32 = (@as(i64, m) * @as(u64, mru)) >> 32;
        const mh: i32 = @bitCast(@as(i32, @intCast(@as(i64, m) * @as(i64, mr) >> 32)));
        const ml: u32 = @bitCast(m *% mr);

        print(multipliers[2]);

        inline for (.{ .{ "mul", ml }, .{ "mulh", mh }, .{ "mulhsu", mhsu }, .{ "mulhu", mhu } }) |tuple| {
            asm volatile (tuple[0] ++
                    \\ %[result], %[op1], %[op2]
                : [result] "=r" (instruction_result),
                : [op1] "r" (m),
                  [op2] "r" (mr),
            );
            assert_eq(instruction_result, @bitCast(tuple[1]), tuple[0]);
        }
    }

    inline for (.{ .{ 0x1234, -0x765, "Div test1\n" }, .{ -0x1234, 0x765, "Div test2\n" }, .{ -0x1234, -0x765, "Div test3\n" }, .{ 0x1234, 0x765, "Div test4\n" } }) |divisions| {
        const d: i32 = divisions[0];
        const dr: i32 = divisions[1];

        const du: u32 = @bitCast(d);
        const dru: u32 = @bitCast(dr);

        const div: i32 = d / dr;
        const divu: u32 = du / @as(u32, dru);
        const rem: i32 = @rem(d, dr);
        const remu: u32 = du % dru;

        print(divisions[2]);

        inline for (.{ .{ "div", div }, .{ "divu", divu }, .{ "rem", rem }, .{ "remu", remu } }) |tuple| {
            asm volatile (tuple[0] ++
                    \\ %[result], %[op1], %[op2]
                : [result] "=r" (instruction_result),
                : [op1] "r" (d),
                  [op2] "r" (dr),
            );
            assert_eq(instruction_result, @bitCast(tuple[1]), tuple[0]);
        }
    }

    print("Testing Edge Cases...\n");

    asm volatile (
        \\add %[result], %[op1], zero
        : [result] "=r" (instruction_result),
        : [op1] "r" (operand1),
    );
    // assert_eq(test_counter, instruction_result);
    print("ADD with zero result written to CSR\n");

    // Test 29: Negative number addition
    test_counter += 1;
    print("Test 29: ADD with negative numbers\n");
    const neg_num: i32 = -100;
    const pos_num: i32 = 50;
    asm volatile (
        \\add %[result], %[neg], %[pos]
        : [result] "=r" (instruction_result),
        : [neg] "r" (neg_num),
          [pos] "r" (pos_num),
    );
    write_test_result(test_counter, instruction_result);
    print("Negative ADD result written to CSR\n");

    // Test 30: Overflow condition
    test_counter += 1;
    print("Test 30: Overflow test\n");
    const max_pos: u32 = 0x7FFFFFFF;
    asm volatile (
        \\addi %[result], %[max], 1
        : [result] "=r" (instruction_result),
        : [max] "r" (max_pos),
    );
    write_test_result(test_counter, instruction_result);
    print("Overflow test result written to CSR\n");

    // Test 31: Shift by zero
    test_counter += 1;
    print("Test 31: Shift by zero\n");
    asm volatile (
        \\sll %[result], %[op1], zero
        : [result] "=r" (instruction_result),
        : [op1] "r" (operand1),
    );
    write_test_result(test_counter, instruction_result);
    print("Shift by zero result written to CSR\n");

    // Test 32: Division by larger number
    test_counter += 1;
    print("Test 32: Division edge case\n");
    asm volatile (
        \\div %[result], %[small], %[large]
        : [result] "=r" (instruction_result),
        : [small] "r" (@as(u32, 100)),
          [large] "r" (@as(u32, 200)),
    );
    write_test_result(test_counter, instruction_result);
    print("Division edge case result written to CSR\n");

    // =============================================================================
    // Final Summary
    // =============================================================================

    print("All tests completed!\n");

    // Write final test summary to CSR
    const final_summary = (0xDEAD << 16) | (test_counter & 0xFFFF);
    asm volatile (
        \\csrrw zero, 0xF, %[summary]
        :
        : [summary] "r" (final_summary),
        : "memory"
    );

    print("Final summary written to CSR 0xF\n");
    print("Test suite execution complete.\n");
}

inline fn write_test_result(test_id: u32, result: u32) void {
    asm volatile (
        \\csrrw zero, 0xF, %[value]
        :
        : [value] "r" (test_id),
        : "memory"
    );
    asm volatile (
        \\csrrw zero, 0xF, %[value]
        :
        : [value] "r" (result),
        : "memory"
    );
}

fn assert_eq(expected: u32, actual: u32, msg: [*:0]const u8) void {
    print("%T");
    print(msg);
    print(" test : ");
    if (expected == actual) {
        print("PASSED\n");
    } else {
        print("FAILED\n");
    }
}

fn alu_test() void {
    const a: i32 = 7;
    const b: i32 = 3;
    const au: u32 = @bitCast(a);
    const bu: u32 = @bitCast(b);
    var result: u32 = undefined;

    // ADD
    asm volatile (
        \\ add %[out], %[lhs], %[rhs]
        : [out] "=r" (result),
        : [lhs] "r" (a),
          [rhs] "r" (b),
    );
    assert_eq(a + b, result, "ADD PASS\n");

    // SUB
    asm volatile (
        \\ sub %[out], %[lhs], %[rhs]
        : [out] "=r" (result),
        : [lhs] "r" (a),
          [rhs] "r" (b),
    );
    assert_eq(a - b, result, "SUB PASS\n");

    // AND
    asm volatile (
        \\ and %[out], %[lhs], %[rhs]
        : [out] "=r" (result),
        : [lhs] "r" (a),
          [rhs] "r" (b),
    );
    assert_eq(a & b, result, "AND PASS\n");

    // OR
    asm volatile (
        \\ or %[out], %[lhs], %[rhs]
        : [out] "=r" (result),
        : [lhs] "r" (a),
          [rhs] "r" (b),
    );
    assert_eq(a | b, result, "OR PASS\n");

    // XOR
    asm volatile (
        \\ xor %[out], %[lhs], %[rhs]
        : [out] "=r" (result),
        : [lhs] "r" (a),
          [rhs] "r" (b),
    );
    assert_eq(a ^ b, result, "XOR PASS\n");

    // SLT (signed)
    asm volatile (
        \\ slt %[out], %[lhs], %[rhs]
        : [out] "=r" (result),
        : [lhs] "r" (a),
          [rhs] "r" (b),
    );
    assert_eq(if (a < b) 1 else 0, result, "SLT PASS\n");

    // SLTU (unsigned)
    asm volatile (
        \\ sltu %[out], %[lhs], %[rhs]
        : [out] "=r" (result),
        : [lhs] "r" (a),
          [rhs] "r" (b),
    );
    assert_eq(if (au < bu) 1 else 0, result, "SLTU PASS\n");

    // SLL
    asm volatile (
        \\ sll %[out], %[lhs], %[rhs]
        : [out] "=r" (result),
        : [lhs] "r" (a),
          [rhs] "r" (b),
    );
    assert_eq(au << bu, result, "SLL PASS\n");

    // SRL
    asm volatile (
        \\ srl %[out], %[lhs], %[rhs]
        : [out] "=r" (result),
        : [lhs] "r" (a),
          [rhs] "r" (b),
    );
    assert_eq(au >> bu, result, "SRL PASS\n");

    // SRA (signed shift)
    asm volatile (
        \\ li %[neg], -8
        : [neg] "=r" (result),
    );
    const neg_val = result;

    asm volatile (
        \\ sra %[out], %[lhs], %[rhs]
        : [out] "=r" (result),
        : [lhs] "r" (neg_val),
          [rhs] "r" (bu),
    );
    assert_eq(@bitCast(-@as(i32, 1)), result, "SRA PASS\n");

    // ADDI
    asm volatile (
        \\ addi %[out], %[lhs], 5
        : [out] "=r" (result),
        : [lhs] "r" (a),
    );
    assert_eq(12, result, "ADDI PASS\n");

    // ANDI
    asm volatile (
        \\ andi %[out], %[lhs], 2
        : [out] "=r" (result),
        : [lhs] "r" (a),
    );
    assert_eq(2, result, "ANDI PASS\n");

    // ORI
    asm volatile (
        \\ ori %[out], %[lhs], 8
        : [out] "=r" (result),
        : [lhs] "r" (b),
    );
    assert_eq(11, result, "ORI PASS\n");

    // XORI
    asm volatile (
        \\ xori %[out], %[lhs], 1
        : [out] "=r" (result),
        : [lhs] "r" (b),
    );
    assert_eq(2, result, "XORI PASS\n");

    // SLTI
    asm volatile (
        \\ slti %[out], %[lhs], 10
        : [out] "=r" (result),
        : [lhs] "r" (b),
    );
    assert_eq(1, result, "SLTI PASS\n");

    // SLTIU
    asm volatile (
        \\ sltiu %[out], %[lhs], 10
        : [out] "=r" (result),
        : [lhs] "r" (b),
    );
    assert_eq(1, result, "SLTIU PASS\n");

    // SLLI
    asm volatile (
        \\ slli %[out], %[lhs], 2
        : [out] "=r" (result),
        : [lhs] "r" (b),
    );
    assert_eq(12, result, "SLLI PASS\n");

    // SRLI
    asm volatile (
        \\ srli %[out], %[lhs], 1
        : [out] "=r" (result),
        : [lhs] "r" (b),
    );
    assert_eq(1, result, "SRLI PASS\n");

    // SRAI
    asm volatile (
        \\ li %[neg], -4
        : [neg] "=r" (result),
    );
    const neg4 = result;
    asm volatile (
        \\ srai %[out], %[lhs], 1
        : [out] "=r" (result),
        : [lhs] "r" (neg4),
    );
    assert_eq(0xFFFFFFFE, result, "SRAI PASS\n");
}
