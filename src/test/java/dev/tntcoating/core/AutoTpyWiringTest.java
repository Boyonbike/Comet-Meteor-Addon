package dev.tntcoating.core;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Check compiled adapter event boundaries without starting Minecraft; behaviour is covered by the coordinator tests. */
class AutoTpyWiringTest {
    @Test void automaticDispatchOnlyRunsFromTheGameTickEvent() throws Exception {
        var type=new ClassNode();
        try(var input=getClass().getResourceAsStream("/dev/tntcoating/modules/ChatTools.class")) {
            assertNotNull(input);new ClassReader(input).accept(type,0);
        }
        var callers=new ArrayList<String>();
        for(var method:type.methods) for(var instruction:method.instructions) {
            if(instruction instanceof MethodInsnNode call && call.owner.equals("dev/tntcoating/core/AutoTpyController") && call.name.equals("tick")) callers.add(method.name);
        }
        assertEquals(List.of("onTick"),callers,"Whispers and GUI actions must not invent extra game ticks");
    }
}
