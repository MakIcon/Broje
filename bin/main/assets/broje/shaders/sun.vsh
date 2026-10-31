#version 450
layout(std140,binding=0) uniform Solar {mat4 SunMVP;vec4 SunColor;};
layout(location=0) out vec2 disc;
void main() {
    const vec2 corners[6]=vec2[6](vec2(-1,-1),vec2(1,-1),vec2(1,1),vec2(-1,-1),vec2(1,1),vec2(-1,1));
    disc=corners[gl_VertexIndex];
    gl_Position=SunMVP*vec4(disc.x,0,disc.y,1);
}
