#version 450
layout(std140,binding=0) uniform Solar {mat4 SunMVP;vec4 SunColor;};
layout(location=0) in vec2 disc;
layout(location=0) out vec4 fragColor;
void main() {
    float radius=length(disc),aa=max(fwidth(radius),0.0005);
    float core=1-smoothstep(0.19-aa,0.19+aa,radius);
    float halo=exp(-radius*radius*22)*0.16*(1-smoothstep(0.65,0.98,radius));
    float coverage=core+(1-core)*halo;
    vec3 rgb=mix(SunColor.rgb,vec3(1,0.97,0.85),core*0.85);
    fragColor=vec4(rgb,coverage*SunColor.a);
}
