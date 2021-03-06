/**
 * Copyright 2011-2015 John Ericksen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.parceler.internal;

import com.sun.codemodel.*;
import org.androidtransfuse.TransfuseAnalysisException;
import org.androidtransfuse.adapter.*;
import org.androidtransfuse.gen.ClassGenerationUtil;
import org.androidtransfuse.gen.ClassNamer;
import org.androidtransfuse.gen.InvocationBuilder;
import org.androidtransfuse.gen.UniqueVariableNamer;
import org.androidtransfuse.model.TypedExpression;
import org.parceler.ParcelConverter;
import org.parceler.ParcelWrapper;
import org.parceler.Parcels;
import org.parceler.internal.generator.ConverterWrapperReadWriteGenerator;
import org.parceler.internal.generator.ParcelReadWriteGenerator;
import org.parceler.internal.generator.ReadWriteGenerator;

import javax.inject.Inject;
import java.util.*;


/**
 * @author John Ericksen
 */
public class ParcelableGenerator {

    private static final String CREATOR_CLASS_NAME = "CREATOR";
    private static final String CREATE_FROM_PARCEL = "createFromParcel";
    private static final String NEW_ARRAY = "newArray";
    private static final String WRITE_TO_PARCEL = "writeToParcel";
    private static final String DESCRIBE_CONTENTS = "describeContents";

    private final JCodeModel codeModel;
    private final UniqueVariableNamer variableNamer;
    private final ClassNamer classNamer;
    private final ClassGenerationUtil generationUtil;
    private final ReadReferenceVisitor readFromParcelVisitor;
    private final WriteReferenceVisitor writeToParcelVisitor;
    private final InvocationBuilder invocationBuilder;
    private final Generators generators;
    private final ParcelReadWriteGenerator parcelReadWriteGenerator;


    @Inject
    public ParcelableGenerator(JCodeModel codeModel,
                               UniqueVariableNamer variableNamer,
                               ClassNamer classNamer,
                               ClassGenerationUtil generationUtil,
                               ReadReferenceVisitor readFromParcelVisitor,
                               WriteReferenceVisitor writeToParcelVisitor,
                               InvocationBuilder invocationBuilder,
                               Generators generators,
                               ParcelReadWriteGenerator parcelReadWriteGenerator) {
        this.codeModel = codeModel;
        this.variableNamer = variableNamer;
        this.classNamer = classNamer;
        this.generationUtil = generationUtil;
        this.readFromParcelVisitor = readFromParcelVisitor;
        this.writeToParcelVisitor = writeToParcelVisitor;
        this.invocationBuilder = invocationBuilder;
        this.generators = generators;
        this.parcelReadWriteGenerator = parcelReadWriteGenerator;
    }

    public JDefinedClass generateParcelable(final ASTType type, ParcelableDescriptor parcelableDescriptor) {
        try {
            JType inputType = generationUtil.ref(type);

            JDefinedClass parcelableClass = generationUtil.defineClass(ClassNamer.className(type).append(Parcels.IMPL_EXT).build());
            parcelableClass._implements(generationUtil.ref("android.os.Parcelable"))
                    ._implements(generationUtil.ref(ParcelWrapper.class).narrow(inputType));

            //wrapped @Parcel
            JFieldVar wrapped = parcelableClass.field(JMod.PRIVATE, inputType, variableNamer.generateName(type));

            //Parcel constructor
            JMethod parcelConstructor = parcelableClass.constructor(JMod.PUBLIC);
            JVar parcelParam = parcelConstructor.param(generationUtil.ref("android.os.Parcel"), variableNamer.generateName("android.os.Parcel"));
            JBlock parcelConstructorBody = parcelConstructor.body();

            //writeToParcel(android.os.Parcel,int)
            JMethod writeToParcelMethod = parcelableClass.method(JMod.PUBLIC, codeModel.VOID, WRITE_TO_PARCEL);
            writeToParcelMethod.annotate(Override.class);
            JVar wtParcelParam = writeToParcelMethod.param(generationUtil.ref("android.os.Parcel"), variableNamer.generateName("android.os.Parcel"));
            JVar flags = writeToParcelMethod.param(codeModel.INT, "flags");

            JBlock writeToParcelMethodBody = writeToParcelMethod.body();
            ReadWriteGenerator rootGenerator = getRootReadWriteGenerator(type);
            JVar writeIdentitySet = writeToParcelMethodBody.decl(codeModel.ref(Set.class).narrow(Integer.class), variableNamer.generateName("identityMap"), JExpr._new(codeModel.ref(HashSet.class).narrow(Integer.class)));
            JVar readIdentityMap = parcelConstructorBody.decl(codeModel.ref(Map.class).narrow(Integer.class, Object.class), variableNamer.generateName("identityMap"), JExpr._new(codeModel.ref(HashMap.class).narrow(Integer.class, Object.class)));
            parcelConstructorBody.assign(wrapped, buildReadFromParcelExpression(parcelConstructorBody, parcelParam, parcelableClass, type, parcelableDescriptor.getParcelConverterType(), rootGenerator, readIdentityMap).getExpression());
            buildWriteToParcelExpression(parcelableClass, writeToParcelMethodBody, wtParcelParam, flags, type, wrapped, parcelableDescriptor.getParcelConverterType(), rootGenerator, writeIdentitySet);

            //@Parcel input
            JMethod inputConstructor = parcelableClass.constructor(JMod.PUBLIC);
            JVar inputParam = inputConstructor.param(inputType, variableNamer.generateName(type));
            inputConstructor.body().assign(wrapped, inputParam);

            //describeContents()
            JMethod describeContentsMethod = parcelableClass.method(JMod.PUBLIC, codeModel.INT, DESCRIBE_CONTENTS);
            describeContentsMethod.annotate(Override.class);
            describeContentsMethod.body()._return(JExpr.lit(0));

            //ParcelWrapper.getParcel()
            JMethod getWrappedMethod = parcelableClass.method(JMod.PUBLIC, inputType, ParcelWrapper.GET_PARCEL);
            getWrappedMethod.annotate(Override.class);
            getWrappedMethod.body()._return(wrapped);

            //public static final CREATOR = ...
            JDefinedClass creatorClass = parcelableClass._class(JMod.PUBLIC | JMod.STATIC | JMod.FINAL, classNamer.numberedClassName(new ASTStringType("android.os.Parcelable.Creator")).build().getClassName());

            creatorClass._implements(generationUtil.ref("android.os.Parcelable.Creator").narrow(parcelableClass));

            //createFromParcel method
            JMethod createFromParcelMethod = creatorClass.method(JMod.PUBLIC, parcelableClass, CREATE_FROM_PARCEL);
            createFromParcelMethod.annotate(Override.class);
            JVar cfpParcelParam = createFromParcelMethod.param(generationUtil.ref("android.os.Parcel"), variableNamer.generateName(generationUtil.ref("android.os.Parcel")));

            createFromParcelMethod.body()._return(JExpr._new(parcelableClass).arg(cfpParcelParam));

            //newArray method
            JMethod newArrayMethod = creatorClass.method(JMod.PUBLIC, parcelableClass.array(), NEW_ARRAY);
            newArrayMethod.annotate(Override.class);
            JVar sizeParam = newArrayMethod.param(codeModel.INT, "size");

            newArrayMethod.body()._return(JExpr.newArray(parcelableClass, sizeParam));

            //public static final Creator<type> CREATOR
            JFieldVar creatorField = parcelableClass.field(JMod.PUBLIC | JMod.STATIC | JMod.FINAL, creatorClass, CREATOR_CLASS_NAME, JExpr._new(creatorClass));
            creatorField.annotate(SuppressWarnings.class).param("value", "UnusedDeclaration");

            return parcelableClass;
        } catch (JClassAlreadyExistsException e) {
            throw new TransfuseAnalysisException("Class Already Exists: " + ClassNamer.className(type).append(Parcels.IMPL_EXT).build(), e);
        }
    }

    public void buildParcelRead(ParcelableDescriptor parcelableDescriptor, JDefinedClass parcelableClass, JVar wrapped, ASTType type, JType inputType, JVar identity, JVar parcelParam, JBlock readFromParcelBody, JVar writeIdentityMap) {
        if (parcelableDescriptor.getParcelConverterType() == null) {

            //constructor
            ConstructorReference constructorPair = parcelableDescriptor.getConstructorPair();
            if(constructorPair == null){
                JInvocation constructorInvocation = JExpr._new(inputType);
                readFromParcelBody.assign(wrapped, constructorInvocation);
            }
            else {
                readFromParcelBody.add(writeIdentityMap.invoke("put").arg(identity).arg(JExpr._null()));
                if(constructorPair.getConstructor() != null){
                    buildReadFromParcelConstructor(parcelableClass, readFromParcelBody, wrapped, constructorPair, type, parcelParam, writeIdentityMap);
                }
                else if(constructorPair.getFactoryMethod() != null){
                    buildReadFromParcelFactoryMethod(parcelableClass, readFromParcelBody, wrapped, constructorPair, type, parcelParam, writeIdentityMap);
                }
            }
            //add to identity map
            readFromParcelBody.add(writeIdentityMap.invoke("put").arg(identity).arg(wrapped));
            //field
            for (ReferencePair<FieldReference> fieldPair : parcelableDescriptor.getFieldPairs()) {
                buildReadFromParcel(parcelableClass, readFromParcelBody, type, wrapped, fieldPair.getReference(), parcelParam, fieldPair.getConverter(), writeIdentityMap);
            }
            //method
            for (ReferencePair<MethodReference> methodPair : parcelableDescriptor.getMethodPairs()) {
                buildReadFromParcel(parcelableClass, readFromParcelBody, type, wrapped, methodPair.getReference(), parcelParam, methodPair.getConverter(), writeIdentityMap);
            }
        } else {
            JClass converterType = generationUtil.ref(parcelableDescriptor.getParcelConverterType());
            JFieldVar converterField = parcelableClass.field(JMod.PRIVATE, converterType,
                    variableNamer.generateName(parcelableDescriptor.getParcelConverterType()), JExpr._new(converterType));

            readFromParcelBody.assign(wrapped, JExpr.invoke(converterField, ParcelConverter.CONVERT_FROM_PARCEL).arg(parcelParam));
        }

        for (ASTMethod unwrapMethod : parcelableDescriptor.getUnwrapCallbacks()) {
            readFromParcelBody.add(invocationBuilder.buildMethodCall(new ASTJDefinedClassType(parcelableClass), type, unwrapMethod, Collections.<JExpression>emptyList(), new TypedExpression(type, wrapped)));
        }
    }

    public void buildParcelWrite(ParcelableDescriptor parcelableDescriptor, JDefinedClass parcelableClass, JExpression wrapped, ASTType type, JExpression wtParcelParam, JVar flags, JBlock writeToParcelBody, JVar writeIdentityMap){

        for (ASTMethod wrapMethod : parcelableDescriptor.getWrapCallbacks()) {
            writeToParcelBody.add(invocationBuilder.buildMethodCall(new ASTJDefinedClassType(parcelableClass), type, wrapMethod, Collections.<JExpression>emptyList(), new TypedExpression(type, wrapped)));
        }

        if (parcelableDescriptor.getParcelConverterType() == null) {

            //constructor
            ConstructorReference constructorPair = parcelableDescriptor.getConstructorPair();
            if(constructorPair != null){
                if(constructorPair.getConstructor() != null){
                    for(ASTParameter parameter : constructorPair.getConstructor().getParameters()){
                        AccessibleReference reference = constructorPair.getWriteReference(parameter);
                        ASTType converter = constructorPair.getConverters().containsKey(parameter) ? constructorPair.getConverters().get(parameter) : null;
                        buildWriteToParcel(parcelableClass, writeToParcelBody, wtParcelParam, flags, reference, type, wrapped, converter, writeIdentityMap);
                    }
                }
                else if(constructorPair.getFactoryMethod() != null){
                    for(ASTParameter parameter : constructorPair.getFactoryMethod().getParameters()){
                        AccessibleReference reference = constructorPair.getWriteReference(parameter);
                        ASTType converter = constructorPair.getConverters().containsKey(parameter) ? constructorPair.getConverters().get(parameter) : null;
                        buildWriteToParcel(parcelableClass, writeToParcelBody, wtParcelParam, flags, reference, type, wrapped, converter, writeIdentityMap);
                    }
                }
            }
            //field
            for (ReferencePair<FieldReference> fieldPair : parcelableDescriptor.getFieldPairs()) {
                buildWriteToParcel(parcelableClass, writeToParcelBody, wtParcelParam, flags, fieldPair.getAccessor(), type, wrapped, fieldPair.getConverter(), writeIdentityMap);
            }
            //method
            for (ReferencePair<MethodReference> methodPair : parcelableDescriptor.getMethodPairs()) {
                buildWriteToParcel(parcelableClass, writeToParcelBody, wtParcelParam, flags, methodPair.getAccessor(), type, wrapped, methodPair.getConverter(), writeIdentityMap);
            }
        } else {
            JClass converterType = generationUtil.ref(parcelableDescriptor.getParcelConverterType());
            JFieldVar converterField = parcelableClass.field(JMod.PRIVATE, converterType,
                    variableNamer.generateName(parcelableDescriptor.getParcelConverterType()), JExpr._new(converterType));

            writeToParcelBody.invoke(converterField, ParcelConverter.CONVERT_TO_PARCEL).arg(wrapped).arg(wtParcelParam);
        }
    }

    private void buildReadFromParcel(JDefinedClass parcelableClass, JBlock parcelConstructorBody, ASTType type, JVar wrapped, AccessibleReference propertyAccessor, JVar parcelParam, ASTType converter, JVar writeIdentityMap) {
        //invocation
        propertyAccessor.accept(readFromParcelVisitor,
                new ReadContext(new ASTJDefinedClassType(parcelableClass),
                        parcelConstructorBody,
                        new TypedExpression(type, wrapped),
                        buildReadFromParcelExpression(parcelConstructorBody, parcelParam, parcelableClass, propertyAccessor.getType(), converter, writeIdentityMap)));
    }

    private void buildReadFromParcelFactoryMethod(JDefinedClass parcelableClass, JBlock parcelConstructorBody, JVar wrapped, ConstructorReference propertyAccessor, ASTType wrappedType, JVar parcelParam, JVar writeIdentityMap){

        ASTMethod factoryMethod = propertyAccessor.getFactoryMethod();
        Map<ASTParameter, ASTType> converters = propertyAccessor.getConverters();

        JInvocation invocation = generationUtil.ref(wrappedType).staticInvoke(factoryMethod.getName());

        for (ASTParameter parameter : factoryMethod.getParameters()) {
            ASTType converter = converters.containsKey(parameter) ? converters.get(parameter) : null;
            JVar var = parcelConstructorBody.decl(generationUtil.ref(parameter.getASTType()),
                    variableNamer.generateName(parameter.getASTType()),
                    buildReadFromParcelExpression(parcelConstructorBody, parcelParam, parcelableClass, parameter.getASTType(), converter, writeIdentityMap).getExpression());
            invocation.arg(var);
        }

        parcelConstructorBody.assign(wrapped, invocation);
    }

    private void buildReadFromParcelConstructor(JDefinedClass parcelableClass, JBlock parcelConstructorBody, JVar wrapped, ConstructorReference propertyAccessor, ASTType wrappedType, JVar parcelParam, JVar writeIdentityMap){

        ASTConstructor constructor = propertyAccessor.getConstructor();
        List<JExpression> inputExpression = new ArrayList<JExpression>();
        Map<ASTParameter, ASTType> converters = propertyAccessor.getConverters();

        for (ASTParameter parameter : constructor.getParameters()) {
            ASTType converter = converters.containsKey(parameter) ? converters.get(parameter) : null;
            JVar var = parcelConstructorBody.decl(generationUtil.ref(parameter.getASTType()),
                    variableNamer.generateName(parameter.getASTType()),
                    buildReadFromParcelExpression(parcelConstructorBody, parcelParam, parcelableClass, parameter.getASTType(), converter, writeIdentityMap).getExpression());
            inputExpression.add(var);
        }

        parcelConstructorBody.assign(wrapped, invocationBuilder.buildConstructorCall(new ASTJDefinedClassType(parcelableClass), constructor, wrappedType, inputExpression));
    }

    private TypedExpression buildReadFromParcelExpression(JBlock body, JVar parcelParam, JDefinedClass parcelableClass, ASTType type, ASTType converter, JVar writeIdentityMap){
        return buildReadFromParcelExpression(body, parcelParam, parcelableClass, type, converter, null, writeIdentityMap);
    }

    private TypedExpression buildReadFromParcelExpression(JBlock body, JVar parcelParam, JDefinedClass parcelableClass, ASTType type, ASTType converter, ReadWriteGenerator overrideGenerator, JVar readIdentityMap){
        JClass returnJClassRef = generationUtil.ref(type);

        ReadWriteGenerator generator;
        if(converter != null){
            generator = new ConverterWrapperReadWriteGenerator(generationUtil.ref(converter));
        }
        else if(overrideGenerator != null) {
            generator = overrideGenerator;
        }
        else{
            generator = generators.getGenerator(type);
        }

        return new TypedExpression(type, generator.generateReader(body, parcelParam, type, returnJClassRef, parcelableClass, readIdentityMap));
    }

    private void buildWriteToParcel(JDefinedClass parcelableClass, JBlock body, JExpression parcel, JVar flags, AccessibleReference reference, ASTType wrappedType, JExpression wrapped, ASTType converter, JVar writeIdentityMap) {
        ASTType type = reference.getType();
        JExpression getExpression = reference.accept(writeToParcelVisitor, new WriteContext(new ASTJDefinedClassType(parcelableClass), new TypedExpression(wrappedType, wrapped)));

        buildWriteToParcelExpression(parcelableClass, body, parcel, flags, type, getExpression, converter, null, writeIdentityMap);
    }

    private void buildWriteToParcelExpression(JDefinedClass parcelableClass, JBlock body, JExpression parcel, JVar flags, ASTType type, JExpression targetExpression, ASTType converter, ReadWriteGenerator overrideGenerator, JVar writeIdentitySet) {

        ReadWriteGenerator generator;
        if(converter != null){
            generator = new ConverterWrapperReadWriteGenerator(generationUtil.ref(converter));
        }
        else if(overrideGenerator != null) {
            generator = overrideGenerator;
        }
        else{
            generator = generators.getGenerator(type);
        }

        generator.generateWriter(body, parcel, flags, type, targetExpression, parcelableClass, writeIdentitySet);
    }

    private ReadWriteGenerator getRootReadWriteGenerator(ASTType type) {
        if(generators.matches(type)){
            return generators.getGenerator(type);
        }
        else {
            return parcelReadWriteGenerator;
        }
    }
}
