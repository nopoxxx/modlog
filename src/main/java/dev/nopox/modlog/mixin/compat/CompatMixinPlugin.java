package dev.nopox.modlog.mixin.compat;

import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies integration mixins only when their mod is installed. A mixin in {@code mixin.compat.<package>} needs the mod
 * listed for that package below.
 */
public final class CompatMixinPlugin implements IMixinConfigPlugin {
    private static final Map<String, String> MOD_BY_PACKAGE = Map.of(
            "sophisticated", "sophisticatedcore",
            "create", "create"
    );

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String rest = mixinClassName.substring(mixinClassName.indexOf(".compat.") + ".compat.".length());
        String modId = MOD_BY_PACKAGE.get(rest.substring(0, rest.indexOf('.')));
        return modId != null && LoadingModList.get().getModFileById(modId) != null;
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
