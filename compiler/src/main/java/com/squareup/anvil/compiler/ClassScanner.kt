package com.squareup.anvil.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.PackageViewDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import kotlin.LazyThreadSafetyMode.NONE

internal class ClassScanner {

  /**
   * Returns a sequence of contributed classes from the dependency graph. Note that the result
   * includes inner classes already.
   */
  fun findContributedClasses(
    module: ModuleDescriptor,
    packageName: String,
    annotation: FqName,
    scope: FqName
  ): Sequence<ClassDescriptor> {
    val packageDescriptor = module.getPackage(FqName(packageName))
    return generateSequence(packageDescriptor.subPackages()) { subPackages ->
      subPackages
          .flatMap { it.subPackages() }
          .ifEmpty { null }
    }
        .flatMap { it.asSequence() }
        .flatMap {
          it.memberScope.getContributedDescriptors(DescriptorKindFilter.VALUES)
              .asSequence()
        }
        .filterIsInstance<PropertyDescriptor>()
        .groupBy { property ->
          // For each contributed hint there are several properties, e.g. the reference itself
          // and the scope. Group them by their common name without the suffix.
          val name = property.name.asString()
          val suffix = propertySuffixes.firstOrNull { name.endsWith(it) } ?: return@groupBy name
          name.substringBeforeLast(suffix)
        }
        .values
        .asSequence()
        .filter { properties ->
          // Double check that the number of properties matches how many suffixes we have and how
          // many properties we expect.
          properties.size == propertySuffixes.size
        }
        .map { ContributedHint(it) }
        .filter { hint ->
          // The scope must match what we're looking for.
          hint.scope.fqNameSafe == scope
        }
        .map { hint -> hint.reference }
        .filter {
          // Check that the annotation really is present. It should always be the case, but it's
          // a safetynet in case the generated properties are out of sync.
          it.annotationOrNull(annotation, scope) != null
        }
  }

  /**
   * Returns a sequence of contributed classes from the dependency graph. Note that the result
   * includes inner classes already.
   */
  // TODO(IR): implement this function with IR declaration properties.
  fun findContributedClasses(
    pluginContext: IrPluginContext,
    moduleFragment: IrModuleFragment,
    packageName: String,
    annotation: FqName,
    scope: FqName
  ): Sequence<IrClassSymbol> {
    return findContributedClasses(moduleFragment.descriptor, packageName, annotation, scope)
        .map {
          pluginContext.requireReferenceClass(it.fqNameSafe)
        }
  }
}

private fun PackageViewDescriptor.subPackages(): List<PackageViewDescriptor> = memberScope
    .getContributedDescriptors(DescriptorKindFilter.PACKAGES)
    .filterIsInstance<PackageViewDescriptor>()

private class ContributedHint(properties: List<PropertyDescriptor>) {
  val reference by lazy(NONE) {
    properties
        .bySuffix(REFERENCE_SUFFIX)
        .toClassDescriptor()
  }

  val scope by lazy(NONE) {
    properties
        .bySuffix(SCOPE_SUFFIX)
        .toClassDescriptor()
  }

  private fun List<PropertyDescriptor>.bySuffix(suffix: String): PropertyDescriptor = first {
    it.name.asString()
        .endsWith(suffix)
  }

  private fun PropertyDescriptor.toClassDescriptor(): ClassDescriptor =
    type.argumentType()
        .classDescriptorForType()
}
