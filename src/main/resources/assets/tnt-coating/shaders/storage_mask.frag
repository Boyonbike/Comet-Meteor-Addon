#version 330 core

in vec4 v_Color;
in float v_Fade;

out vec4 color;

void main() {
    // Invisible nearby models must also leave the mask depth clear for models behind them.
    if (v_Fade < 0.075 || v_Color.a <= 0.0) discard;
    color = v_Color;
}
