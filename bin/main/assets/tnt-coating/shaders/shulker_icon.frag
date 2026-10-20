#version 330

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};
uniform sampler2D Sampler0;
in vec2 texCoord0;
in vec4 vertexColor;
out vec4 fragColor;

void main() {
    // Keep only the icon's silhouette. Texture colour, shading and glint cannot tint the chosen colour.
    if (texture(Sampler0, texCoord0).a <= 0.01) discard;
    vec4 colour = vertexColor * ColorModulator;
    fragColor = vec4(colour.rgb * colour.a, colour.a);
}
