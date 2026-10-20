#version 330 core

layout (location = 0) in vec4 Position;
layout (location = 1) in vec4 Color;

layout (std140) uniform MeshData {
    mat4 u_Proj;
    mat4 u_ModelView;
};

layout (std140) uniform StorageData {
    vec4 playerAndFade;
};

out vec4 v_Color;
out float v_Fade;

void main() {
    gl_Position = u_Proj * u_ModelView * Position;
    vec3 delta = Position.xyz - playerAndFade.xyz;
    float fadeDistance = playerAndFade.w;
    v_Fade = fadeDistance > 0.0
        ? min(dot(delta, delta) / (fadeDistance * fadeDistance), 1.0)
        : 1.0;
    v_Color = vec4(Color.rgb, Color.a * v_Fade);
}
