package com.example.blockmemory;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.BlockItem;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Map;

/**
 * BlockMemoryMod — Fabric 1.21.6
 *
 *  R : save blocks (20-block radius, at player Y and above)
 *  T : toggle rendering
 *  V / B : lower / raise the render ceiling (slice viewer)
 *  Right-click on a blue ghost → auto-switch to matching block, place it,
 *                               then return to the cached empty slot on release
 */
public class BlockMemoryMod implements ClientModInitializer {

	/*──── configuration & state ──────────────────────────────*/
	private static final int RADIUS = 20;                  // save/scan radius
	private static final double RAY_STEP = 0.25;           // ray-march step
	private static final double MAX_REACH = 5.0;           // placement reach

	private static final Map<BlockPos, BlockState> savedBlocks = new HashMap<>();
	private static boolean renderEnabled = false;

	private static int minRenderY  = 0;                    // player Y when saved
	private static int maxSavedY   = 0;                    // tallest captured block
	private static int renderMaxY  = 0;                    // slice ceiling

	/* hot-bar swap bookkeepintg */
	private static int  cachedEmptySlot = -1;
	private static boolean isPlacing    = false;

	/*──── key bindings ───────────────────────────────────────*/
	private static final KeyBinding SAVE_KEY   = KeyBindingHelper.registerKeyBinding(
			new KeyBinding("key.blockmemory.save_blocks",   GLFW.GLFW_KEY_R, "category.blockmemory"));

	private static final KeyBinding TOGGLE_KEY = KeyBindingHelper.registerKeyBinding(
			new KeyBinding("key.blockmemory.toggle_render", GLFW.GLFW_KEY_T, "category.blockmemory"));

	private static final KeyBinding DOWN_KEY   = KeyBindingHelper.registerKeyBinding(
			new KeyBinding("key.blockmemory.render_down",   GLFW.GLFW_KEY_V, "category.blockmemory"));

	private static final KeyBinding UP_KEY     = KeyBindingHelper.registerKeyBinding(
			new KeyBinding("key.blockmemory.render_up",     GLFW.GLFW_KEY_B, "category.blockmemory"));

	/*─────────────────────────────────────────────────────────*/

	@Override
	public void onInitializeClient() {

		/* per-tick handler */
		ClientTickEvents.END_CLIENT_TICK.register(client -> {

			/* ---- basic keys ---- */
			if (SAVE_KEY.wasPressed())  saveBlocks(client);
			if (TOGGLE_KEY.wasPressed()) {
				renderEnabled = !renderEnabled;
				client.player.sendMessage(
						Text.literal("Rendering " + (renderEnabled ? "enabled" : "disabled")),
						false);
			}
			if (DOWN_KEY.wasPressed() && renderMaxY > minRenderY) {
				renderMaxY--;
				client.player.sendMessage(Text.literal("Render Y ≤ " + renderMaxY)
						.formatted(Formatting.GRAY), true);
			}
			if (UP_KEY.wasPressed() && renderMaxY < maxSavedY) {
				renderMaxY++;
				client.player.sendMessage(Text.literal("Render Y ≤ " + renderMaxY)
						.formatted(Formatting.GRAY), true);
			}

			/* ---- placement logic ---- */
			ClientPlayerEntity player = client.player;
			World world               = client.world;
			if (player == null || world == null || !renderEnabled || savedBlocks.isEmpty())
				return;

			boolean usePressed    = client.options.useKey.isPressed();
			boolean useJustPressed= client.options.useKey.wasPressed();

			/* find the first ghost block under the cross-hair (custom ray-march) */
			BlockPos ghostPos = findGhostInSight(player, world);

			/* button released → return to cached empty slot */
			if (!usePressed && isPlacing) {
				player.getInventory().setSelectedSlot(cachedEmptySlot);
				player.networkHandler.sendPacket(
						new UpdateSelectedSlotC2SPacket(cachedEmptySlot));
				isPlacing = false;
			}

			/* button pressed on a ghost → swap & place */
			if (useJustPressed && ghostPos != null) {
				BlockState wanted = savedBlocks.get(ghostPos);
				int blockSlot = findBlockSlot(player, wanted);
				int emptySlot = findEmptySlot(player);

				if (blockSlot != -1 && emptySlot != -1) {
					cachedEmptySlot = emptySlot;

					player.getInventory().setSelectedSlot(blockSlot);
					player.networkHandler.sendPacket(
							new UpdateSelectedSlotC2SPacket(blockSlot));
					isPlacing = true;

					/* send vanilla place packet */
					BlockHitResult bhr = new BlockHitResult(
							Vec3d.ofCenter(ghostPos),
							Direction.UP,            // face doesn't matter for air
							ghostPos,
							false);
					client.interactionManager.interactBlock(
							player, Hand.MAIN_HAND, bhr);
				} else {
					player.sendMessage(Text.literal(
									"Need that block plus an empty slot in the hot-bar")
							.formatted(Formatting.RED), true);
				}
			}
		});

		/* render hook */
		WorldRenderEvents.AFTER_ENTITIES.register(this::renderSavedBlocks);
	}

	/*──────────────────────── saving ────────────────────────*/
	private void saveBlocks(MinecraftClient client) {
		World world   = client.world;
		ClientPlayerEntity player = client.player;
		if (world == null || player == null) return;

		BlockPos origin = player.getBlockPos();
		savedBlocks.clear();

		minRenderY = origin.getY();
		int highest = minRenderY;

		for (int dx = -RADIUS; dx <= RADIUS; dx++) {
			for (int dy = 0; dy <= RADIUS; dy++) {        // only same Y & up
				for (int dz = -RADIUS; dz <= RADIUS; dz++) {
					BlockPos p = origin.add(dx, dy, dz);
					BlockState s = world.getBlockState(p);
					if (!s.isAir()) {
						savedBlocks.put(p.toImmutable(), s);
						if (p.getY() > highest) highest = p.getY();
					}
				}
			}
		}
		maxSavedY  = highest;
		renderMaxY = maxSavedY;

		player.sendMessage(Text.literal("Saved " + savedBlocks.size() +
				" blocks (Y " + minRenderY +
				"–" + maxSavedY + ')'), false);
	}

	/*──────────────────────── rendering ─────────────────────*/
	private void renderSavedBlocks(WorldRenderContext ctx) {
		if (!renderEnabled || savedBlocks.isEmpty()) return;

		MatrixStack matrices = ctx.matrixStack();
		double camX = ctx.camera().getPos().x;
		double camY = ctx.camera().getPos().y;
		double camZ = ctx.camera().getPos().z;

		VertexConsumer lineBuf = ctx.consumers().getBuffer(RenderLayer.getLines());
		BlockRenderManager brm = MinecraftClient.getInstance().getBlockRenderManager();

		matrices.push();
		matrices.translate(-camX, -camY, -camZ);

		for (var entry : savedBlocks.entrySet()) {
			BlockPos pos   = entry.getKey();
			BlockState state= entry.getValue();

			if (pos.getY() > renderMaxY) continue;                 // slice filter
			if (ctx.world().getBlockState(pos).equals(state)) continue; // already built

			/* model */
			matrices.push();
			matrices.translate(pos.getX(), pos.getY(), pos.getZ());
			brm.renderBlockAsEntity(state, matrices, ctx.consumers(),
					0xF000F0, OverlayTexture.DEFAULT_UV);
			matrices.pop();

			/* outline */
			VoxelShape shape = state.getOutlineShape(ctx.world(), pos);
			if (!shape.isEmpty()) {
				shape.forEachBox((minX, minY, minZ, maxX, maxY, maxZ) ->
						drawBox(lineBuf, matrices,
								pos.getX()+minX, pos.getY()+minY, pos.getZ()+minZ,
								pos.getX()+maxX, pos.getY()+maxY, pos.getZ()+maxZ,
								0, 0, 1, 1));  // blue RGBA
			}
		}
		matrices.pop();
	}

	/*──────────────────────── utilities ─────────────────────*/

	/** march along the look-vector and return the first air-ghost inside reach */
	private static BlockPos findGhostInSight(ClientPlayerEntity p, World w) {
		Vec3d eye  = p.getCameraPosVec(1);
		Vec3d dir  = p.getRotationVec(1).normalize();

		for (double t = 0; t <= MAX_REACH; t += RAY_STEP) {
			BlockPos pos = BlockPos.ofFloored(eye.add(dir.multiply(t)));
			BlockState wanted = savedBlocks.get(pos);
			if (wanted != null && w.getBlockState(pos).isAir()) return pos;
		}
		return null;
	}

	private static int findEmptySlot(ClientPlayerEntity p) {
		for (int i = 0; i < 9; i++)
			if (p.getInventory().getStack(i).isEmpty()) return i;
		return -1;
	}

	private static int findBlockSlot(ClientPlayerEntity p, BlockState wanted) {
		for (int i = 0; i < 9; i++) {
			if (p.getInventory().getStack(i).getItem() instanceof BlockItem bi &&
					bi.getBlock().asItem() == wanted.getBlock().asItem())
				return i;
		}
		return -1;
	}

	/** simple blue wire-box */
	private static void drawBox(VertexConsumer buf, MatrixStack ms,
								double x0, double y0, double z0,
								double x1, double y1, double z1,
								float r, float g, float b, float a) {
		MatrixStack.Entry m = ms.peek();
		float[][] v = {
				{(float)x0,(float)y0,(float)z0},{(float)x1,(float)y0,(float)z0},
				{(float)x1,(float)y0,(float)z1},{(float)x0,(float)y0,(float)z1},
				{(float)x0,(float)y1,(float)z0},{(float)x1,(float)y1,(float)z0},
				{(float)x1,(float)y1,(float)z1},{(float)x0,(float)y1,(float)z1}};
		int[][] e = {{0,1},{1,2},{2,3},{3,0},{4,5},{5,6},{6,7},{7,4},{0,4},{1,5},{2,6},{3,7}};
		for (int[] ed : e) {
			float[] a0=v[ed[0]], a1=v[ed[1]];
			buf.vertex(m.getPositionMatrix(), a0[0], a0[1], a0[2]).color(r,g,b,a).normal(0,0,1);
			buf.vertex(m.getPositionMatrix(), a1[0], a1[1], a1[2]).color(r,g,b,a).normal(0,0,1);
		}
	}
}
