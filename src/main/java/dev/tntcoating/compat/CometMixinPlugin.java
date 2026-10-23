package dev.tntcoating.compat;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies a Comet upgrade to a Meteor module only if that Meteor build still has everything the upgrade targets.
 * Otherwise the upgrade is skipped and the stock module keeps working, instead of the client crashing at startup.
 * Only bytecode is read here; no Meteor or Minecraft class is loaded.
 */
public final class CometMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOG = LoggerFactory.getLogger("Comet");
    private final Map<String, Boolean> decisions = new HashMap<>();
    private String mixinPackage = "";

    @Override public void onLoad(String mixinPackage) { this.mixinPackage = mixinPackage; }

    @Override public String getRefMapperConfig() { return null; }

    @Override public synchronized boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        Boolean decided = decisions.get(mixinClassName);
        if (decided != null) return decided;
        MeteorUpgradeCheck.ClassNodes classes = name -> MixinService.getService().getBytecodeProvider()
            .getClassNode(name.replace('/', '.'));
        String member = simpleName(mixinClassName);
        List<String> problems = check(mixinPackage.isEmpty() ? member : mixinPackage + "." + member, classes);
        boolean apply = problems.isEmpty();
        decisions.put(mixinClassName, apply);
        if (!apply) {
            String reason = String.join("; ", problems);
            MeteorUpgrades.disable(member, reason);
            LOG.warn("Skipping Comet's {}: this Meteor build is not compatible ({})", member, reason);
        }
        return apply;
    }

    private static List<String> check(String mixin, MeteorUpgradeCheck.ClassNodes classes) {
        ClassNode node;
        try {
            node = classes.get(mixin);
        } catch (Exception e) {
            // Leave the decision to Mixin itself, exactly as without this plugin.
            return List.of();
        }
        if (node == null || !MeteorUpgradeCheck.targetsMeteor(node)) return List.of();
        return MeteorUpgradeCheck.problems(node, classes);
    }

    private static String simpleName(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }

    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override public List<String> getMixins() { return null; }

    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
