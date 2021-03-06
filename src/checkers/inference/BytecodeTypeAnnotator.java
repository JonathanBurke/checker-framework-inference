package checkers.inference;

import checkers.inference.model.VariableSlot;
import checkers.inference.quals.VarAnnot;
import checkers.inference.util.ConstantToVariableAnnotator;
import checkers.inference.util.CopyUtil;
import org.checkerframework.framework.qual.Unqualified;
import org.checkerframework.framework.type.AnnotatedTypeFactory;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.framework.util.AnnotationBuilder;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;

import java.lang.annotation.Annotation;
import java.util.HashMap;
import java.util.Map;


/**
 * Adds annotations to types that have come from bytecode.  Note, these types will
 * receive concrete "real" annotations from the real type factory AND a VarAnnot.
 *
 * The VarAnnot is so that we have an annotation in both hierarchies and so that
 * for cases like Verigames the shape of a gameboard won't differ depending on
 * whether a library was in bytecode or source code.  Note: I believe this is
 * much less of an issue these days since the game is more akin to
 * human aided automatic solving.
 */
public class BytecodeTypeAnnotator {

    private final SlotManager slotManager;

    //see org.checkerframework.framework.qual.Unqualified
    private final AnnotationMirror unqualified;

    //see checkers.inference.quals.VarAnnot
    private final AnnotationMirror varAnnot;
    private final AnnotatedTypeFactory realTypeFactory;

    private Map<Class<? extends Annotation>, VariableSlot> constantToVarAnnot = new HashMap<>();

    public BytecodeTypeAnnotator(AnnotatedTypeFactory realTypeFactory,
                                 Map<Class<? extends Annotation>, VariableSlot> constantToVarAnnot) {
        slotManager = InferenceMain.getInstance().getSlotManager();
        unqualified = new AnnotationBuilder(realTypeFactory.getProcessingEnv(), Unqualified.class).build();
        varAnnot = new AnnotationBuilder(realTypeFactory.getProcessingEnv(), VarAnnot.class).build();
        this.realTypeFactory = realTypeFactory;

        this.constantToVarAnnot = constantToVarAnnot;
    }

    /**
     * Get the type of element from the realTypeFactory.  Copy it's annotations to inferenceType.
     * Add a @VarAnnot to all defaultable locations in inferenceType and add an equality constraint
     * between it and the "real" annotations
     * @param element The bytecode declaration from which inferenceType was created
     * @param inferenceType The type of element.  inferenceType will be annotated by this method
     */
    public void annotate(final Element element, final AnnotatedTypeMirror inferenceType) {
        final AnnotatedTypeMirror realType = realTypeFactory.getAnnotatedType(element);

        CopyUtil.copyAnnotations(realType, inferenceType);
        new ConstantToVariableAnnotator(unqualified, varAnnot, slotManager, constantToVarAnnot).visit(inferenceType);
    }
}
