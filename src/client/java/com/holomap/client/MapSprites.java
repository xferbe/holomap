package com.holomap.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;

/** Ícones do mapa vanilla (atlas {@code map_decorations}). */
public final class MapSprites {
	private MapSprites() {
	}

	public static TextureAtlasSprite player() {
		return Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.MAP_DECORATIONS)
			.getSprite(Identifier.withDefaultNamespace("player"));
	}
}
