#version 330
#extension GL_ARB_separate_shader_objects : require

// Igual ao core/text do jogo, com uma diferença: a malha da maquete está no espaço do mapa (0 a 128), não em
// coordenadas relativas à câmera. A distância da neblina sai da posição já transformada para a câmera; com a
// posição crua, a borda do mapa parecia estar a 128 blocos e sumia na neblina.

#include <minecraft:fog.glsl>
#include <minecraft:sample_lightmap.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV2;

uniform sampler2D Sampler2;
layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec2 texCoord0;

void main() {
    vec4 viewPos = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * viewPos;

    float distance = length(viewPos.xyz);
    sphericalVertexDistance = distance;
    cylindricalVertexDistance = distance;
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
    texCoord0 = UV0;
}
