package com.apollographql.apollo3.compiler.codegen.java.file

import com.apollographql.apollo3.ast.QueryDocumentMinifier
import com.apollographql.apollo3.compiler.applyIf
import com.apollographql.apollo3.compiler.codegen.Identifier
import com.apollographql.apollo3.compiler.codegen.Identifier.OPERATION_DOCUMENT
import com.apollographql.apollo3.compiler.codegen.Identifier.OPERATION_ID
import com.apollographql.apollo3.compiler.codegen.Identifier.OPERATION_NAME
import com.apollographql.apollo3.compiler.codegen.Identifier.customScalarAdapters
import com.apollographql.apollo3.compiler.codegen.Identifier.document
import com.apollographql.apollo3.compiler.codegen.Identifier.id
import com.apollographql.apollo3.compiler.codegen.Identifier.name
import com.apollographql.apollo3.compiler.codegen.Identifier.root
import com.apollographql.apollo3.compiler.codegen.java.CodegenJavaFile
import com.apollographql.apollo3.compiler.codegen.java.JavaClassBuilder
import com.apollographql.apollo3.compiler.codegen.java.JavaClassNames
import com.apollographql.apollo3.compiler.codegen.java.JavaContext
import com.apollographql.apollo3.compiler.codegen.java.L
import com.apollographql.apollo3.compiler.codegen.java.S
import com.apollographql.apollo3.compiler.codegen.java.T
import com.apollographql.apollo3.compiler.codegen.java.helpers.Builder
import com.apollographql.apollo3.compiler.codegen.java.helpers.makeDataClassFromParameters
import com.apollographql.apollo3.compiler.codegen.java.helpers.maybeAddDescription
import com.apollographql.apollo3.compiler.codegen.java.helpers.toNamedType
import com.apollographql.apollo3.compiler.codegen.java.helpers.toParameterSpec
import com.apollographql.apollo3.compiler.codegen.java.model.ModelBuilder
import com.apollographql.apollo3.compiler.codegen.maybeFlatten
import com.apollographql.apollo3.compiler.ir.IrOperation
import com.apollographql.apollo3.compiler.ir.IrOperationType
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.CodeBlock
import com.squareup.javapoet.FieldSpec
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.ParameterizedTypeName
import com.squareup.javapoet.TypeName
import com.squareup.javapoet.TypeSpec
import javax.lang.model.element.Modifier

internal class OperationBuilder(
    private val context: JavaContext,
    private val operationId: String,
    private val generateQueryDocument: Boolean,
    private val operation: IrOperation,
    private val generateDataBuilders: Boolean,
    flatten: Boolean,
) : JavaClassBuilder {
  private val layout = context.layout
  private val packageName = layout.operationPackageName(operation.filePath)
  private val simpleName = layout.operationName(operation)

  private val dataSuperClassName = when (operation.operationType) {
    is IrOperationType.Query -> JavaClassNames.QueryData
    is IrOperationType.Mutation -> JavaClassNames.MutationData
    is IrOperationType.Subscription -> JavaClassNames.SubscriptionData
  }

  private val modelBuilders = operation.dataModelGroup.maybeFlatten(
      flatten = flatten,
      excludeNames = setOf(simpleName)
  ).flatMap {
    it.models
  }.map {
    ModelBuilder(
        context = context,
        model = it,
        superClassName = if (it.id == operation.dataModelGroup.baseModelId) dataSuperClassName else null,
        path = listOf(packageName, simpleName),
    )
  }

  override fun prepare() {
    context.resolver.registerOperation(
        operation.name,
        ClassName.get(packageName, simpleName)
    )
    modelBuilders.forEach { it.prepare() }
  }

  override fun build(): CodegenJavaFile {
    return CodegenJavaFile(
        packageName = packageName,
        typeSpec = typeSpec()
    )
  }

  fun typeSpec(): TypeSpec {
    return TypeSpec.classBuilder(layout.operationName(operation))
        .addModifiers(Modifier.PUBLIC)
        .addSuperinterface(superInterfaceType())
        .maybeAddDescription(operation.description)
        .makeDataClassFromParameters(operation.variables.map { it.toNamedType().toParameterSpec(context) })
        .addBuilder(context)
        .addMethod(operationIdMethodSpec())
        .addMethod(queryDocumentMethodSpec(generateQueryDocument))
        .addMethod(nameMethodSpec())
        .addMethod(serializeVariablesMethodSpec())
        .addMethod(adapterMethodSpec(context.resolver, operation.dataProperty))
        .addMethod(rootFieldMethodSpec())
        .applyIf(generateDataBuilders) {
          addMethod(buildDataMethod())
          addMethod(buildDataOverloadMethod())
        }
        .addTypes(dataTypeSpecs())
        .addField(
            FieldSpec.builder(JavaClassNames.String, OPERATION_ID)
                .addModifiers(Modifier.FINAL)
                .addModifiers(Modifier.STATIC)
                .addModifiers(Modifier.PUBLIC)
                .initializer(S, operationId)
                .build()
        )
        .applyIf(generateQueryDocument) {
          addField(FieldSpec.builder(JavaClassNames.String, OPERATION_DOCUMENT)
              .addModifiers(Modifier.FINAL)
              .addModifiers(Modifier.STATIC)
              .addModifiers(Modifier.PUBLIC)
              .initializer(S, QueryDocumentMinifier.minify(operation.sourceWithFragments))
              .addJavadoc(L, """
                The minimized GraphQL document being sent to the server to save a few bytes.
                The un-minimized version is:


                """.trimIndent() + operation.sourceWithFragments.escapeKdoc()
              )
              .build()
          )
        }
        .addField(FieldSpec
            .builder(JavaClassNames.String, OPERATION_NAME)
            .addModifiers(Modifier.FINAL)
            .addModifiers(Modifier.STATIC)
            .addModifiers(Modifier.PUBLIC)
            .initializer(S, operation.name)
            .build()
        )
        .build()
  }

  //  public static Data buildData(QueryMap queryMap, FakeResolver resolver) {
//    return FakeResolverKt.buildData(
//        GetIntQuery_ResponseAdapter.Data.INSTANCE,
//        GetIntQuerySelections.__root,
//        "Query",
//        queryMap,
//        resolver
//    );
//  }
//
//  public static Data buildData(QueryMap queryMap) {
//    return buildData(queryMap, new DefaultFakeResolver(__Schema.types));
//  }
  private fun buildDataMethod(): MethodSpec {
    return MethodSpec.methodBuilder(Identifier.buildData)
        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
        .addParameter(ClassName.get(layout.builderPackageName(), layout.mapName(operation.operationType.typeName)), Identifier.map)
        .addParameter(JavaClassNames.FakeResolver, Identifier.resolver)
        .returns(context.resolver.resolveModel(operation.dataModelGroup.baseModelId))
        .addCode(
            CodeBlock.builder()
                .add("return $T.buildData(\n", JavaClassNames.FakeResolverKt)
                .indent()
                .add("$L.INSTANCE,\n", context.resolver.resolveModelAdapter(operation.dataModelGroup.baseModelId))
                .add("$T.$root,\n", context.resolver.resolveOperationSelections(operation.name))
                .add("$S,\n", operation.operationType.typeName)
                .add("${Identifier.map},\n")
                .add("${Identifier.resolver},\n")
                .add("$T.$customScalarAdapters\n", context.resolver.resolveSchema())
                .unindent()
                .add(");")
                .build()
        )
        .build()
  }

  private fun buildDataOverloadMethod(): MethodSpec {
    return MethodSpec.methodBuilder(Identifier.buildData)
        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
        .addParameter(ClassName.get(layout.builderPackageName(), layout.mapName(operation.operationType.typeName)), Identifier.map)
        .returns(context.resolver.resolveModel(operation.dataModelGroup.baseModelId))
        .addStatement(
            "return buildData(${Identifier.map}, new $T($T.types))",
            JavaClassNames.DefaultFakeResolver,
            context.resolver.resolveSchema()
        )
        .build()
  }


  private fun serializeVariablesMethodSpec(): MethodSpec = serializeVariablesMethodSpec(
      adapterClassName = context.resolver.resolveOperationVariablesAdapter(operation.name),
      emptyMessage = "This operation doesn't have any variable"
  )

  private fun dataTypeSpecs(): List<TypeSpec> {
    return modelBuilders.map {
      it.build()
    }
  }

  private fun superInterfaceType(): TypeName {
    return when (operation.operationType) {
      is IrOperationType.Query -> JavaClassNames.Query
      is IrOperationType.Mutation -> JavaClassNames.Mutation
      is IrOperationType.Subscription -> JavaClassNames.Subscription
    }.let {
      ParameterizedTypeName.get(it, context.resolver.resolveModel(operation.dataModelGroup.baseModelId))
    }
  }

  private fun operationIdMethodSpec() = MethodSpec.methodBuilder(id)
      .addModifiers(Modifier.PUBLIC)
      .addAnnotation(JavaClassNames.Override)
      .returns(JavaClassNames.String)
      .addStatement("return $OPERATION_ID")
      .build()

  private fun queryDocumentMethodSpec(generateQueryDocument: Boolean) = MethodSpec.methodBuilder(document)
      .addModifiers(Modifier.PUBLIC)
      .addAnnotation(JavaClassNames.Override)
      .returns(JavaClassNames.String)
      .apply {
        if (generateQueryDocument) {
          addStatement("return $OPERATION_DOCUMENT")
        } else {
          addStatement("error(\"The query document was removed from this operation. Use generateQueryDocument.set(true) if you need it\")")
        }
      }
      .build()

  private fun nameMethodSpec() = MethodSpec.methodBuilder(name)
      .addModifiers(Modifier.PUBLIC)
      .addAnnotation(JavaClassNames.Override)
      .returns(JavaClassNames.String)
      .addStatement("return $OPERATION_NAME")
      .build()

  /**
   * Things like `[${'$'}oo]` do not compile. See https://youtrack.jetbrains.com/issue/KT-43906
   */
  private fun String.escapeKdoc(): String {
    return replace("[", "\\[").replace("]", "\\]")
  }

  private fun rootFieldMethodSpec(): MethodSpec {
    return rootFieldMethodSpec(
        context,
        operation.typeCondition,
        context.resolver.resolveOperationSelections(operation.name)
    )
  }

  private fun TypeSpec.Builder.addBuilder(context: JavaContext): TypeSpec.Builder {
    addMethod(Builder.builderFactoryMethod())

    val operationClassName = ClassName.get(packageName, simpleName)

    if (operation.variables.isEmpty()) {
      return addType(
          Builder(
              targetObjectClassName = operationClassName,
              fields = emptyList(),
              fieldJavaDocs = emptyMap(),
              context = context
          ).build()
      )
    }

    operation.variables
        .map {
          context.layout.propertyName(it.name) to context.resolver.resolveIrType(it.type)
        }
        .let {
          Builder(
              targetObjectClassName = operationClassName,
              fields = it,
              fieldJavaDocs = emptyMap(),
              context = context
          )
        }
        .let { addType(it.build()) }

    return this
  }
}
