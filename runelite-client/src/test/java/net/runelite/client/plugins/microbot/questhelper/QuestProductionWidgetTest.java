package net.runelite.client.plugins.microbot.questhelper;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import net.runelite.api.ItemID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class QuestProductionWidgetTest
{
    @Test
    public void productionHighlightSelectsTheRequestedItemChildInsteadOfTheInterfaceParent() throws Exception
    {
        Widget root = visibleWidget(-1);
        Widget wrongChoice = visibleWidget(ItemID.WOOL);
        Widget choiceContainer = visibleWidget(-1);
        Widget ballOfWoolChoice = visibleWidget(ItemID.BALL_OF_WOOL);

        when(root.getChildren()).thenReturn(new Widget[]{wrongChoice, choiceContainer});
        when(choiceContainer.getDynamicChildren()).thenReturn(new Widget[]{ballOfWoolChoice});

        Method selector;
        try
        {
            selector = QuestScript.class.getDeclaredMethod("findVisibleWidgetByItemId", Widget.class, int.class);
        }
        catch (NoSuchMethodException ex)
        {
            fail("QuestScript must resolve the exact item child in a production interface");
            return;
        }
        selector.setAccessible(true);

        assertSame(ballOfWoolChoice, selector.invoke(null, root, ItemID.BALL_OF_WOOL));
    }

    @Test
    public void productionItemHighlightsUseTheMicrobotMakeAllProcessingFlow() throws IOException
    {
        ProcessingCalls calls = readProcessingCalls();

        assertTrue("Production must select a quantity before choosing the output", calls.quantityOrder > 0);
        assertEquals("All", calls.quantity);
        assertTrue("Production must use the processing-interface API after selecting All",
                calls.processingOrder > calls.quantityOrder);
    }

    private static Widget visibleWidget(int itemId)
    {
        Widget widget = mock(Widget.class);
        when(widget.isHidden()).thenReturn(false);
        when(widget.getItemId()).thenReturn(itemId);
        return widget;
    }

    private static ProcessingCalls readProcessingCalls() throws IOException
    {
        String resource = "/" + Type.getInternalName(QuestScript.class) + ".class";
        ProcessingCalls calls = new ProcessingCalls();
        try (InputStream input = QuestScript.class.getResourceAsStream(resource))
        {
            if (input == null)
            {
                throw new IOException("Unable to load " + resource);
            }

            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions)
                {
                    return new MethodVisitor(Opcodes.ASM9)
                    {
                        private int callOrder;
                        private String lastStringConstant;

                        @Override
                        public void visitLdcInsn(Object value)
                        {
                            if (value instanceof String)
                            {
                                lastStringConstant = (String) value;
                            }
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String methodName,
                                                    String methodDescriptor, boolean isInterface)
                        {
                            callOrder++;
                            if (!owner.equals(Type.getInternalName(Rs2Widget.class)))
                            {
                                return;
                            }
                            if (methodName.equals("enableQuantityOption"))
                            {
                                calls.quantityOrder = callOrder;
                                calls.quantity = lastStringConstant;
                            }
                            if (methodName.equals("handleProcessingInterface"))
                            {
                                calls.processingOrder = callOrder;
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_FRAMES);
        }
        return calls;
    }

    private static final class ProcessingCalls
    {
        private int quantityOrder;
        private int processingOrder;
        private String quantity;
    }
}
