#!/usr/bin/env python3
"""Render the real icon shaders offscreen and verify pixel colours (Linux EGL/OpenGL)."""

import ctypes as C
from pathlib import Path
import re
import struct
import zipfile


ROOT = Path(__file__).resolve().parents[1]
EGL = C.CDLL("libEGL.so.1")
EGL.eglGetProcAddress.argtypes = [C.c_char_p]
EGL.eglGetProcAddress.restype = C.c_void_p


def egl(name, result, *args):
    method = getattr(EGL, name)
    method.restype, method.argtypes = result, args
    return method


def gl(name, result, *args):
    address = EGL.eglGetProcAddress(name.encode())
    assert address, name
    return C.CFUNCTYPE(result, *args)(address)


def main():
    get_display = C.CFUNCTYPE(C.c_void_p, C.c_uint, C.c_void_p, C.POINTER(C.c_int))(
        EGL.eglGetProcAddress(b"eglGetPlatformDisplayEXT")
    )
    display = get_display(0x31DD, None, None)  # EGL_PLATFORM_SURFACELESS_MESA
    major, minor = C.c_int(), C.c_int()
    assert egl("eglInitialize", C.c_uint, C.c_void_p, C.c_void_p, C.c_void_p)(display, C.byref(major), C.byref(minor))
    assert egl("eglBindAPI", C.c_uint, C.c_uint)(0x30A2)  # OpenGL
    attributes = (C.c_int * 13)(0x3033, 1, 0x3040, 8, 0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3021, 8, 0x3038)
    config, count = C.c_void_p(), C.c_int()
    assert egl("eglChooseConfig", C.c_uint, C.c_void_p, C.c_void_p, C.c_void_p, C.c_int, C.c_void_p)(
        display, attributes, C.byref(config), 1, C.byref(count)
    ) and count.value
    context_attributes = (C.c_int * 7)(0x3098, 3, 0x30FB, 3, 0x30FD, 1, 0x3038)
    context = egl("eglCreateContext", C.c_void_p, C.c_void_p, C.c_void_p, C.c_void_p, C.c_void_p)(
        display, config, None, context_attributes
    )
    surface_attributes = (C.c_int * 5)(0x3057, 4, 0x3056, 4, 0x3038)
    surface = egl("eglCreatePbufferSurface", C.c_void_p, C.c_void_p, C.c_void_p, C.c_void_p)(display, config, surface_attributes)
    assert context and surface
    assert egl("eglMakeCurrent", C.c_uint, C.c_void_p, C.c_void_p, C.c_void_p, C.c_void_p)(display, surface, surface, context)
    renderer = gl("glGetString", C.c_char_p, C.c_uint)(0x1F01).decode()

    create_shader = gl("glCreateShader", C.c_uint, C.c_uint)
    shader_source = gl("glShaderSource", None, C.c_uint, C.c_int, C.c_void_p, C.c_void_p)
    compile_shader = gl("glCompileShader", None, C.c_uint)
    get_shader = gl("glGetShaderiv", None, C.c_uint, C.c_uint, C.c_void_p)
    get_shader_log = gl("glGetShaderInfoLog", None, C.c_uint, C.c_int, C.c_void_p, C.c_void_p)
    create_program = gl("glCreateProgram", C.c_uint)
    attach_shader = gl("glAttachShader", None, C.c_uint, C.c_uint)
    link_program = gl("glLinkProgram", None, C.c_uint)
    get_program = gl("glGetProgramiv", None, C.c_uint, C.c_uint, C.c_void_p)
    use_program = gl("glUseProgram", None, C.c_uint)

    def program(vertex, fragment):
        result = create_program()
        for kind, source in [(0x8B31, vertex), (0x8B30, fragment)]:
            shader = create_shader(kind)
            source_pointer = C.c_char_p(source.encode())
            shader_source(shader, 1, C.byref(source_pointer), None)
            compile_shader(shader)
            status = C.c_int()
            get_shader(shader, 0x8B81, C.byref(status))
            log = C.create_string_buffer(8192)
            get_shader_log(shader, len(log), None, log)
            assert status.value, log.value.decode()
            attach_shader(result, shader)
        link_program(result)
        status = C.c_int()
        get_program(result, 0x8B82, C.byref(status))
        assert status.value, "shader link failed"
        return result

    shader_path = ROOT / "src/main/resources/assets/tnt-coating/shaders"
    solid = program((shader_path / "shulker_icon.vert").read_text(), (shader_path / "shulker_icon.frag").read_text())
    version_catalog = (ROOT / "gradle/libs.versions.toml").read_text()
    minecraft_version = re.search(r'^minecraft = "([^"]+)"$', version_catalog, re.MULTILINE).group(1)
    minecraft = next((ROOT / ".gradle/loom-cache/minecraftMaven").rglob(
        f"minecraft-merged-*-{minecraft_version}.jar"
    ))
    with zipfile.ZipFile(minecraft) as jar:
        normal = program(jar.read("assets/minecraft/shaders/core/position_tex_color.vsh").decode(),
                         jar.read("assets/minecraft/shaders/core/position_tex_color.fsh").decode())

    gen_buffers = gl("glGenBuffers", None, C.c_int, C.c_void_p)
    bind_buffer = gl("glBindBuffer", None, C.c_uint, C.c_uint)
    buffer_data = gl("glBufferData", None, C.c_uint, C.c_ssize_t, C.c_void_p, C.c_uint)
    bind_base = gl("glBindBufferBase", None, C.c_uint, C.c_uint, C.c_uint)
    block_index = gl("glGetUniformBlockIndex", C.c_uint, C.c_uint, C.c_char_p)
    block_binding = gl("glUniformBlockBinding", None, C.c_uint, C.c_uint, C.c_uint)
    identity = [1.0 if i % 5 == 0 else 0.0 for i in range(16)]
    for binding, (name, values) in enumerate([
        (b"DynamicTransforms", identity + [1.0] * 4 + [0.0] * 4 + identity),
        (b"Projection", identity),
    ]):
        buffer = C.c_uint()
        gen_buffers(1, C.byref(buffer))
        bind_buffer(0x8A11, buffer)
        data = struct.pack(f"{len(values)}f", *values)
        buffer_data(0x8A11, len(data), data, 0x88E4)
        bind_base(0x8A11, binding, buffer)
        for pipeline in [solid, normal]:
            block_binding(pipeline, block_index(pipeline, name), binding)

    vao, vbo, texture = C.c_uint(), C.c_uint(), C.c_uint()
    gl("glGenVertexArrays", None, C.c_int, C.c_void_p)(1, C.byref(vao))
    gl("glBindVertexArray", None, C.c_uint)(vao)
    gen_buffers(1, C.byref(vbo))
    gl("glGenTextures", None, C.c_int, C.c_void_p)(1, C.byref(texture))
    gl("glBindTexture", None, C.c_uint, C.c_uint)(0x0DE1, texture)
    parameter = gl("glTexParameteri", None, C.c_uint, C.c_uint, C.c_int)
    parameter(0x0DE1, 0x2801, 0x2600)
    parameter(0x0DE1, 0x2800, 0x2600)
    # An icon mask with holes, semi-transparent texels, and unrelated texture/shading colours.
    source_pixels = [(i * 13, 255 - i * 11, i * 7, [0, 255, 128, 32][i % 4]) for i in range(16)]
    pixels = bytes(channel for pixel in source_pixels for channel in pixel)
    gl("glTexImage2D", None, C.c_uint, C.c_int, C.c_int, C.c_int, C.c_int, C.c_int, C.c_uint, C.c_uint, C.c_void_p)(
        0x0DE1, 0, 0x8058, 4, 4, 0, 0x1908, 0x1401, pixels
    )
    gl("glViewport", None, C.c_int, C.c_int, C.c_int, C.c_int)(0, 0, 4, 4)
    read_pixels = gl("glReadPixels", None, C.c_int, C.c_int, C.c_int, C.c_int, C.c_uint, C.c_uint, C.c_void_p)
    attrib = gl("glGetAttribLocation", C.c_int, C.c_uint, C.c_char_p)
    vertex_pointer = gl("glVertexAttribPointer", None, C.c_uint, C.c_int, C.c_uint, C.c_uint, C.c_int, C.c_void_p)
    enable_attrib = gl("glEnableVertexAttribArray", None, C.c_uint)

    def draw(pipeline, colour):
        use_program(pipeline)
        vertices = []
        for x, y in [(-1, -1), (1, -1), (1, 1), (-1, -1), (1, 1), (-1, 1)]:
            vertices.extend([x, y, 0, (x + 1) / 2, (y + 1) / 2, *(value / 255 for value in colour)])
        data = struct.pack(f"{len(vertices)}f", *vertices)
        bind_buffer(0x8892, vbo)
        buffer_data(0x8892, len(data), data, 0x88E0)
        for name, size, offset in [(b"Position", 3, 0), (b"UV0", 2, 12), (b"Color", 4, 20)]:
            location = attrib(pipeline, name)
            enable_attrib(location)
            vertex_pointer(location, size, 0x1406, 0, 36, C.c_void_p(offset))
        gl("glClearColor", None, C.c_float, C.c_float, C.c_float, C.c_float)(0, 0, 0, 0)
        gl("glClear", None, C.c_uint)(0x4000)
        gl("glDrawArrays", None, C.c_uint, C.c_int, C.c_int)(4, 0, 6)
        output = (C.c_ubyte * 64)()
        read_pixels(0, 0, 4, 4, 0x1908, 0x1401, output)
        return [tuple(output[i:i + 4]) for i in range(0, 64, 4)]

    checks = 0
    for colour in [(221, 30, 70, 255), (0, 255, 0, 255), (20, 40, 230, 255),
                   (0, 0, 0, 255), (255, 255, 255, 255), (233, 82, 11, 128), (255, 0, 0, 0)]:
        for source, actual in zip(source_pixels, draw(solid, colour)):
            expected = (0, 0, 0, 0) if source[3] == 0 else tuple(round(c * colour[3] / 255) for c in colour[:3]) + (colour[3],)
            assert all(abs(a - b) <= 1 for a, b in zip(actual, expected)), (colour, source, actual, expected)
            checks += 1
        # Switching solid colour off must restore the original item design with no leaked tint.
        for source, actual in zip(source_pixels, draw(normal, (255, 255, 255, 255))):
            expected = source if source[3] else (0, 0, 0, 0)
            assert all(abs(a - b) <= 1 for a, b in zip(actual, expected)), (source, actual)
            checks += 1
    print(f"PASS: {checks} rendered-pixel checks; solid RGB/alpha, holes, and normal-mode restoration. Renderer: {renderer}")
    egl("eglMakeCurrent", C.c_uint, C.c_void_p, C.c_void_p, C.c_void_p, C.c_void_p)(display, None, None, None)
    egl("eglDestroySurface", C.c_uint, C.c_void_p, C.c_void_p)(display, surface)
    egl("eglDestroyContext", C.c_uint, C.c_void_p, C.c_void_p)(display, context)
    egl("eglTerminate", C.c_uint, C.c_void_p)(display)


if __name__ == "__main__":
    main()
