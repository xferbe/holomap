package com.holomap.client.diorama;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;

/** Maquete já na GPU: subida uma vez, desenhada todo frame só com a matriz do quadro. */
final class DioramaMesh implements AutoCloseable {
	final GpuBuffer solid;
	final int solidQuads;
	final GpuBuffer water;
	final int waterQuads;

	private DioramaMesh(GpuBuffer solid, int solidQuads, GpuBuffer water, int waterQuads) {
		this.solid = solid;
		this.solidQuads = solidQuads;
		this.water = water;
		this.waterQuads = waterQuads;
	}

	/** Só na thread de render. */
	static DioramaMesh upload(DioramaBuilder.Built built, String label) {
		try (built) {
			GpuBuffer solid = built.solid() == null ? null
				: RenderSystem.getDevice().createBuffer(() -> label + " solid", GpuBuffer.USAGE_VERTEX, built.solid().vertexBuffer());
			GpuBuffer water = built.water() == null ? null
				: RenderSystem.getDevice().createBuffer(() -> label + " water", GpuBuffer.USAGE_VERTEX, built.water().vertexBuffer());
			return new DioramaMesh(solid, built.solidQuads(), water, built.waterQuads());
		}
	}

	@Override
	public void close() {
		if (solid != null) solid.close();
		if (water != null) water.close();
	}
}
