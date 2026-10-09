package com.holomap.client.diorama;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;

/** Maquete já na GPU: subida uma vez, desenhada todo frame só com a matriz do quadro. */
final class DioramaMesh implements AutoCloseable {
	final GpuBuffer solid;
	final int solidQuads;
	final GpuBuffer water;
	final int waterQuads;
	/** Memória de vídeo ocupada pelos vértices. */
	final long bytes;

	private DioramaMesh(GpuBuffer solid, int solidQuads, GpuBuffer water, int waterQuads, long bytes) {
		this.solid = solid;
		this.solidQuads = solidQuads;
		this.water = water;
		this.waterQuads = waterQuads;
		this.bytes = bytes;
	}

	/** Só na thread de render. */
	static DioramaMesh upload(DioramaBuilder.Built built, String label) {
		try (built) {
			long bytes = 0;
			GpuBuffer solid = null, water = null;
			if (built.solid() != null) {
				bytes += built.solid().vertexBuffer().remaining();
				solid = RenderSystem.getDevice().createBuffer(() -> label + " solid", GpuBuffer.USAGE_VERTEX, built.solid().vertexBuffer());
			}
			if (built.water() != null) {
				bytes += built.water().vertexBuffer().remaining();
				water = RenderSystem.getDevice().createBuffer(() -> label + " water", GpuBuffer.USAGE_VERTEX, built.water().vertexBuffer());
			}
			return new DioramaMesh(solid, built.solidQuads(), water, built.waterQuads(), bytes);
		}
	}

	int quads() {
		return solidQuads + waterQuads;
	}

	@Override
	public void close() {
		if (solid != null) solid.close();
		if (water != null) water.close();
	}
}
