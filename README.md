# Holomap

Mod Fabric para Minecraft **26.3**: o mapa colocado num item frame vira uma **maquete 3D do terreno**, com as texturas reais dos blocos e a tinta de bioma do jogo, atualizada em tempo real.

- Árvore aparece como árvore (tronco e copa), casa como casa (paredes e telhado), água azul translúcida por cima do fundo, e o relevo com corte de terra e pedra nas bordas.
- Colocou ou quebrou um bloco na área do mapa, a maquete se refaz sozinha em menos de meio segundo.
- Mapas vizinhos lado a lado (num chão ou numa parede) formam um mapa 3D grande, do mesmo jeito que o vanilla junta os mapas planos.
- O quadro fica invisível quando tem mapa: sobra só a maquete. É só visual; dá para tirar o mapa normalmente.
- Os jogadores aparecem com a seta do mapa vanilla, na altura em que estão, inclusive quem está longe.

Não tem tecla, tela nem configuração: é só pendurar o mapa.

## Instalação

O mesmo `.jar` serve para cliente e servidor. Precisa do **Fabric Loader 0.19.5+** e do **Fabric API** para 26.3.

1. Rode `gradlew build` e pegue `build/libs/holomap-0.1.0.jar`.
2. Coloque o jar e o Fabric API na pasta `mods` de **todo mundo que vai jogar**.
3. Para jogar em dupla: um abre o mundo para LAN e o outro entra. O servidor integrado de quem abriu roda a parte de servidor do mod.

Num servidor dedicado, o jar vai também na pasta `mods` do servidor. Se o servidor não tiver o mod, a maquete ainda aparece, mas só com o terreno que o seu cliente tem carregado.

## Como funciona

```text
servidor (integrado ou dedicado)                     cliente
────────────────────────────────                     ───────
chunk carrega/muda ─► pilha de blocos de cada ─┐
                     coluna (topo → chão firme) │     chunks perto de você: lidos
                                                ▼     localmente, refeitos no tick
               terreno guardado (memória + save)      seguinte a qualquer bloco
                                                │               │
cliente: "estou vendo mapas desta área" ────────┤               ▼
                                                ▼        terreno 3D do cliente
         só o que mudou e o cliente não tem ──────────►         │
                                                                ▼
                     cópia da área (thread do jogo) ─► malha (outra thread) ─► GPU
                                                                                  │
                     quadro com mapa: desenha a malha com a matriz do quadro ◄────┘
```

Para cada coluna, o mod guarda a pilha de blocos do topo até o chão firme (o primeiro bloco sólido e opaco que não é tronco nem folha), mais o bioma. Abaixo disso a coluna é tratada como maciça. O cliente transforma isso em cubos usando o modelo de cada bloco: textura de cada face com as camadas (a lateral da grama tem duas), tinta de bioma e sombreamento por face igual ao do terreno. Por isso resource packs também aparecem na maquete.

A maquete fica no espaço do próprio mapa (128×128, com a direção e a rotação do quadro). Assim funciona em quadro no chão, na parede e no teto. A altura usa a mesma escala do mapa: num mapa de escala 0, um bloco de altura vale um pixel do mapa. A base fica 24 blocos abaixo do nível do mar.

### Desempenho

- **Uma malha por mapa, montada uma vez e guardada na GPU**: a cada frame só se desenha com a matriz do quadro, sem reenviar vértices.
- **Montagem fora da thread do jogo**: a thread do jogo só copia a área do mapa (uma por tick, no máximo), e a geração das faces roda numa thread separada de baixa prioridade.
- **Só faces visíveis**: face encostada em bloco opaco não é gerada, e folha encostada em folha vira bloco cheio, como no gráfico rápido.
- **Nível de detalhe por distância**: até 8 blocos, cada coluna; até 20, células de 2×2; até 40, de 4×4; além disso, 8×8.
- **Remonta só quando a área muda**: cada chunk guarda quando mudou, e a maquete confere a própria área a cada 4 ticks, refazendo no máximo 4 vezes por segundo.
- **Servidor**: mudar um bloco só marca o chunk, e a releitura acontece no máximo a cada 0,5 s. A rede manda só o que mudou e o que o cliente não tem carregado.

## Desenvolvimento

```bash
gradlew build
```

```bash
gradlew runClient
```

```bash
gradlew runClientGameTest
```

O último abre o jogo num mundo de seed fixa, monta uma mesa de mapa e uma parede 2×2 e salva screenshots em `build/run/clientGameTest/screenshots`.

| Pasta | Conteúdo |
| --- | --- |
| `src/main` | Comum e servidor: leitura das colunas, rede, terreno guardado no save |
| `src/client` | Cliente: terreno 3D, visual dos blocos, montagem e desenho da maquete |
| `src/gametest` | Teste automático com screenshots |
