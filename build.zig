const std = @import("std");
const Target = @import("std").Target;
const Feature = @import("std").Target.Cpu.Feature;

// Although this function looks imperative, note that its job is to
// declaratively construct a build graph that will be executed by an external
// runner.
pub fn build(b: *std.Build) void {
    var disabled_features = Feature.Set.empty;
    var enabled_features = Feature.Set.empty;

    for ([_]Target.riscv.Feature{ .a, .c, .d, .e, .f }) |f| {
        disabled_features.addFeature(@intFromEnum(f));
    }

    for ([_]Target.riscv.Feature{.m}) |f| {
        enabled_features.addFeature(@intFromEnum(f));
    }

    const target = b.resolveTargetQuery(std.Target.Query{
        .cpu_arch = Target.Cpu.Arch.riscv32,
        .os_tag = Target.Os.Tag.freestanding,
        .abi = Target.Abi.none,
        .cpu_model = .{ .explicit = &std.Target.riscv.cpu.generic_rv32 },
        .cpu_features_add = enabled_features,
        .cpu_features_sub = disabled_features,
    });

    const optimise = b.standardOptimizeOption(.{ .preferred_optimize_mode = .ReleaseSmall });

    const lib_mod = b.createModule(.{
        // `root_source_file` is the Zig "entry point" of the module. If a module
        // only contains e.g. external object files, you can make this `null`.
        // In this case the main source file is merely a path, however, in more
        // complicated build scripts, this could be a generated file.
        .root_source_file = b.path("src/root.zig"),
        .target = target,
        .optimize = optimise,
    });

    // We will also create a module for our other entry point, 'main.zig'.
    const exe_mod = b.createModule(.{
        // `root_source_file` is the Zig "entry point" of the module. If a module
        // only contains e.g. external object files, you can make this `null`.
        // In this case the main source file is merely a path, however, in more
        // complicated build scripts, this could be a generated file.
        .root_source_file = b.path("src/main.zig"),
        .target = target,
        .optimize = optimise,
    });

    exe_mod.addImport("ChiselRV32_lib", lib_mod);

    const exe = b.addExecutable(.{
        .name = "ChiselRV32",
        .root_module = exe_mod,
    });

    exe.setLinkerScript(b.path("src/linker.ld"));

    const bin = exe.addObjCopy(.{ .format = .bin });
    const generate_bin = b.addInstallBinFile(bin.getOutput(), "rv32im_test.bin");

    const converter_mod = b.createModule(.{ .root_source_file = b.path("bin_to_hex.zig"), .target = b.standardTargetOptions(.{}) });
    const converter = b.addExecutable(.{
        .name = "bin_to_hex",
        .root_module = converter_mod,
    });
    const convert_to_hex_step = b.addRunArtifact(converter);
    convert_to_hex_step.addFileArg(generate_bin.source);
    const output = convert_to_hex_step.addOutputFileArg("rv32im_test.hex");
    convert_to_hex_step.step.dependOn(&generate_bin.step);
    // const custom_output = b.addInstallArtifact(artifact: *Compile, options: Options)

    b.getInstallStep().dependOn(&b.addInstallFileWithDir(output, .prefix, "rv32im_test.hex").step);

    const lib_unit_tests = b.addTest(.{
        .root_module = lib_mod,
    });

    const run_lib_unit_tests = b.addRunArtifact(lib_unit_tests);

    const exe_unit_tests = b.addTest(.{
        .root_module = exe_mod,
    });

    const run_exe_unit_tests = b.addRunArtifact(exe_unit_tests);

    // Similar to creating the run step earlier, this exposes a `test` step to
    // the `zig build --help` menu, providing a way for the user to request
    // running the unit tests.
    const test_step = b.step("test", "Run unit tests");
    test_step.dependOn(&run_lib_unit_tests.step);
    test_step.dependOn(&run_exe_unit_tests.step);
}

fn writeCoe(bin_path: []const u8, coe_path: []const u8) anyerror!void {
    const allocator = std.heap.page_allocator;
    std.debug.print("Reading from {s}\n", .{bin_path});
    const fs = std.fs;

    const input_file = try fs.cwd().openFile(bin_path, .{ .mode = .read_only });
    defer input_file.close();

    const output_file = try fs.cwd().createFile(coe_path, .{ .mode = 0, .truncate = true });
    defer output_file.close();

    // Write COE header
    try output_file.writeAll("memory_initialization_radix=16;\nmemory_initialization_vector=\n");

    // Read binary data and write as hex
    var reader = input_file.reader();
    // var buffer: [16]u8 = undefined;
    // var first = true;
    const buffer: []u8 = try reader.readAllAlloc(allocator, 128000);
    defer allocator.free(buffer);

    for (buffer) |byte| {
        try std.fmt.format(output_file.writer(), "{x}", .{byte});
    }

    // End COE file
    try output_file.writeAll(";\n");
}
