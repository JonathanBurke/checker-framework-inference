package checkers.inference;

import checkers.inference.model.ExistentialVariableSlot;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.framework.util.AnnotationBuilder;
import org.checkerframework.javacutil.AnnotationUtils;
import org.checkerframework.javacutil.ErrorReporter;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;

import checkers.inference.model.CombVariableSlot;
import checkers.inference.model.ConstantSlot;
import checkers.inference.model.RefinementVariableSlot;
import checkers.inference.model.Slot;
import checkers.inference.model.VariableSlot;
import checkers.inference.quals.VarAnnot;

import static checkers.inference.InferenceQualifierHierarchy.isVarAnnot;

/**
 * The default implementation of SlotManager.
 * @see checkers.inference.SlotManager
 */
public class DefaultSlotManager implements SlotManager {

    private final AnnotationMirror varAnnot;
    //this id starts at 1 because sin ome serializer's (CnfSerializer) 0 is used as line delimiters
    //monotonically increasing id for all VariableSlots (including subtypes of VariableSlots)
    private int nextId = 1;

    //a map of variable id to variable for ALL variables (including subtypes of VariableSlots)
    private final Map<Integer, VariableSlot> variables;

    private final Set<Class<? extends Annotation>> realQualifiers;
    private final ProcessingEnvironment processingEnvironment;

    public DefaultSlotManager( final ProcessingEnvironment processingEnvironment,
                               final Set<Class<? extends Annotation>> realQualifiers ) {
        this.processingEnvironment = processingEnvironment;
        this.realQualifiers = realQualifiers;
        variables = new LinkedHashMap<>();

        AnnotationBuilder builder = new AnnotationBuilder(processingEnvironment, VarAnnot.class);
        builder.setValue("value", -1 );
        this.varAnnot = builder.build();
    }

    /**
     * @inheritDoc
     */
    @Override
    public int nextId() {
        return nextId++;
    }

    /**
     * @inheritDoc
     */
    @Override
    public void addVariable( final VariableSlot slot ) {
        variables.put( slot.getId(), slot );
    }

    /**
     * @inheritDoc
     */
    @Override
    public VariableSlot getVariable( int id ) {
        return variables.get(id);
    }

    /**
     * @inheritDoc
     */
    @Override
    public AnnotationMirror getAnnotation( final Slot slot ) {
        final Class<?> slotClass = slot.getClass();

        // We need to build the AnntotationBuilder each time because AnnotationBuilders are only allowed to build their annotations once
        if( slotClass.equals( VariableSlot.class )
                || slotClass.equals(ExistentialVariableSlot.class )
                || slotClass.equals( RefinementVariableSlot.class )
                || slotClass.equals( CombVariableSlot.class ) ) {
            return convertVariable( (VariableSlot) slot, new AnnotationBuilder( processingEnvironment, VarAnnot.class) );
        }

        if( slotClass.equals( ConstantSlot.class ) ) {
            return ((ConstantSlot) slot).getValue();
        }

        throw new IllegalArgumentException("Slot type unrecognized( " + slot.getClass() + ") Slot=" + slot.toString() );
    }

    /**
     * Converts the given VariableSlot into an annotation using the given AnnotationBuiklder
     * @param variable VariableSlot to convert
     * @param annotationBuilder appropriate annotation for the actual class of the VariableSlot which could be subtype
     *                          of VariableSlot.  Eg.  CombVariableSlots use combVarBuilder which is parameterized to
     *                          build @CombVarAnnots
     * @return An annotation representing variable
     */
    private AnnotationMirror convertVariable( final VariableSlot variable, final AnnotationBuilder annotationBuilder) {
        annotationBuilder.setValue("value", variable.getId() );
        return annotationBuilder.build();
    }

    /**
     * @inheritDoc
     */
    @Override
    public VariableSlot getVariableSlot( final AnnotatedTypeMirror atm ) {

        final AnnotationMirror varAnnot = atm.getAnnotationInHierarchy(this.varAnnot);
        if (varAnnot == null && InferenceMain.isHackMode()) {
            InferenceMain.getInstance().logger.warning("Hack:DefaultSlotManager:121");
            return null;
        } else if (varAnnot == null) {
            ErrorReporter.errorAbort("Missing VarAnnot annotation: " + atm);
        }

        return (VariableSlot) getSlot(varAnnot);
    }

    /**
     * @inheritDoc
     */
    @Override
    public Slot getSlot( final AnnotationMirror annotationMirror ) {

        final int id;
        if( isVarAnnot(annotationMirror) ) {
            if(annotationMirror.getElementValues().isEmpty() ) {
                return null; //TODO: should we instead throw an exception?
            } else {
                final AnnotationValue annoValue = annotationMirror.getElementValues().values().iterator().next();
                id = Integer.valueOf( annoValue.toString() );
            }

            return getVariable( id );

        } else {
            for( Class<? extends Annotation> realAnno : realQualifiers ) {
                if( AnnotationUtils.areSameByClass(annotationMirror, realAnno)) {
                    return new ConstantSlot( annotationMirror );
                }
            }
        }

        if (InferenceMain.isHackMode()) {
            InferenceMain.getInstance().logger.warning("Hack:DefaultSlotManager:146");
            return new ConstantSlot(InferenceMain.getInstance().getRealTypeFactory().
                    getQualifierHierarchy().getTopAnnotations().iterator().next());
        }
        ErrorReporter.errorAbort( annotationMirror + " is a type of AnnotationMirror not handled by getVariableSlot." );
        return null; // Dead
    }

    /**
     * @inheritDoc
     */
    @Override
    public List<Slot> getSlots() {
        return new ArrayList<Slot>( this.variables.values() );
    }

    // Sometimes, I miss scala.
    /**
     * @inheritDoc
     */
    @Override
    public List<VariableSlot> getVariableSlots() {
        List<VariableSlot> varSlots = new ArrayList<>();
        for (Slot slot : variables.values()) {
            if (slot instanceof VariableSlot) {
                varSlots.add((VariableSlot) slot);
            }
        }
        return varSlots;
    }
}