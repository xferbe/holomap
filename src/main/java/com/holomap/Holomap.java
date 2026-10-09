package com.holomap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.holomap.net.HolomapNet;
import com.holomap.server.HolomapServer;

import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;

public class Holomap implements ModInitializer {
	public static final String MOD_ID = "holomap";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		HolomapNet.register();
		HolomapServer.init();
	}
}
