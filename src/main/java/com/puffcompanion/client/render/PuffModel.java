package com.puffcompanion.client.render;

import com.puffcompanion.PuffCompanion;
import com.puffcompanion.entity.PuffEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;

/**
 * Resolves to:
 * <ul>
 *   <li>geo/entity/mouse_girl_base.geo.json</li>
 *   <li>textures/entity/mouse_girl_base.png</li>
 *   <li>animations/entity/mouse_girl_base.animation.json</li>
 * </ul>
 * and turns the {@code Head} bone toward the look direction.
 */
public class PuffModel extends DefaultedEntityGeoModel<PuffEntity> {
    public static final String BASE_NAME = "mouse_girl_base";

    public PuffModel() {
        super(new ResourceLocation(PuffCompanion.MODID, BASE_NAME), "Head");
    }
}
