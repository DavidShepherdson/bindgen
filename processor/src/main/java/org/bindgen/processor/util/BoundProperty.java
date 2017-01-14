package org.bindgen.processor.util;

import static org.bindgen.processor.CurrentEnv.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;

import joist.util.Copy;
import joist.util.Inflector;
import joist.util.Join;

import org.bindgen.binding.GenericObjectBindingPath;
import org.bindgen.processor.CurrentEnv;

/** Given a TypeMirror type of a field/method property, provides information about its binding outer/inner class. */
public class BoundProperty {

	private final TypeElement enclosing;
	private final TypeMirror type;
	private final Element element;
	private final String propertyName;
	private final boolean isFixingRawType;
	private final boolean isArray;
	private ClassName name;

	/**
	 * @param enclosed the parent method or field
	 * @param type our type
	 * @param propertyName our name on the parent <code>enclosed</code> type
	 */
	public BoundProperty(TypeElement outerElement, Element enclosed, TypeMirror type, String propertyName) {
		this.enclosing = (TypeElement) enclosed.getEnclosingElement();
		this.propertyName = propertyName;

		type = Util.resolveTypeVarIfPossible(getTypeUtils(), outerElement, type).type;
		this.isArray = type.getKind() == TypeKind.ARRAY;
		// if we're an array, keep the primitive type, e.g. char[]
		this.type = this.isArray ? type : Util.boxIfNeeded(type);
		this.element = getTypeUtils().asElement(Util.boxIfNeeded(type));
		this.name = new ClassName(this.type.toString());
		this.isFixingRawType = this.fixRawTypeIfNeeded();
	}

	public boolean isForGenericTypeParameter() {
		return this.isTypeParameter(this.element);
	}

	public boolean shouldSkip() {
		return this.isDeclaringClass() || this.isSkipAttributeSet() || this.isForBinding();
	}

	public String getCastForReturnIfNeeded() {
		return (this.hasWildcards() && !this.isArray()) ? "(" + this.getSetType() + ") " : "";
	}

	private String optionalGenericsIfWildcards(String replace) {
		if (this.type.getKind() == TypeKind.DECLARED) {
			List<String> dummyParams = new ArrayList<String>();
			if (!this.isRawType()) {
				for (TypeMirror tm : ((DeclaredType) this.type).getTypeArguments()) {
					if (tm.getKind() == TypeKind.WILDCARD && replace != null) {
						dummyParams.add(replace);
					}
				}
			}
			if (dummyParams.size() > 0) {
				return "<" + Join.commaSpace(dummyParams) + ">";
			}
		}
		return "";
	}

	public String getBindingRootClassInstantiation() {
		return "My" + Inflector.capitalize(this.propertyName) + "Binding" + this.optionalGenericsIfWildcards(null);
	}

	public String getBindingClassFieldDeclaration() {
		return this.getInnerClassSuperClass(true);
	}

	public String getInnerClassSuperClass() {
		return this.getInnerClassSuperClass(false);
	}

	public String getInnerClassDeclaration() {
		String name = "My" + Inflector.capitalize(this.propertyName) + "Binding";
		if (this.type.getKind() == TypeKind.DECLARED) {
			List<String> dummyParams = new ArrayList<String>();
			if (this.isRawType()) {
				for (TypeParameterElement tpe : this.getElement().getTypeParameters()) {
					dummyParams.add(tpe.toString());
				}
			} else {
				// the user declared wildcards, e.g. "public Foo<?> foo", but
				// our MyFoo inner class can't use wildcards in the declaration,
				// so we make up dummy Ux type parameters and s/?/Ux

				// First pass: work out what the dummy type names will be for the existing wildcards.
				int wildcardIndex = 0;
				Map<TypeMirror, String> dummyTypes = new HashMap<TypeMirror, String>();
				List<WildcardTypeData> wildcardList = new ArrayList<WildcardTypeData>();
				for (TypeMirror tm : ((DeclaredType) this.type).getTypeArguments()) {
					final String dummyParam = "U" + (wildcardIndex);
					TypeMirror relevantType = null;
					if (tm.getKind() == TypeKind.WILDCARD) {
						WildcardTypeData wildcard = new WildcardTypeData(dummyParam);
						WildcardType wt = (WildcardType) tm;
						if (wt.getExtendsBound() != null) {
							// the user declared their own bounds, e.g. "public Foo<? extends Foo<?>> foo"
							relevantType = wt.getExtendsBound();
							wildcard = new WildcardTypeData(dummyParam, wt.getExtendsBound());
						} else {
							// get Foo<?>'s declared type, and copy over its type parameters
							// and their bounds, replacing ? with our U0
							Element e = ((DeclaredType) this.type).asElement();
							if (e instanceof TypeElement) {
								List<? extends TypeParameterElement> tpes = ((TypeElement) e).getTypeParameters();
								if (tpes.size() > wildcardIndex) {
									TypeParameterElement tp = tpes.get(wildcardIndex);
									relevantType = tp.asType();
									if (tp.getBounds().size() > 0) {
										wildcard = new WildcardTypeData(dummyParam, tp);
									}
								}
							}
						}
						dummyTypes.put(relevantType, dummyParam);
						wildcardList.add(wildcard);
						wildcardIndex++;
					}
				}

				// Second pass: work out the 'extends' suffix for each dummy param.
				for (WildcardTypeData wildcard : wildcardList) {
					String suffix = "";
					if (wildcard.extendsBound != null) {
						// the user declared their own bounds, e.g. "public Foo<? extends Foo<?>> foo"
						suffix += " extends " + wildcard.extendsBound.toString();
					} else if (wildcard.wildcardParameter != null) {
						suffix += " extends "
							+ toStringWithDummyParam(
								wildcard.wildcardParameter.getBounds().get(0),
								wildcard.wildcardParameter.asType(),
								wildcard.dummyParam,
								dummyTypes);
					}
					dummyParams.add(wildcard.dummyParam + suffix);
				}
			}
			if (dummyParams.size() > 0) {
				name += "<" + Join.commaSpace(dummyParams) + ">";
			}
		}
		return name;
	}

	/** @return whether or not bindgen should generate a binding class for this properties' type */
	public boolean shouldGenerateBindingClassForType() {
		return CurrentEnv.getConfig().shouldGenerateBindingFor(this.name);
	}

	public boolean existsFieldTypeBindingFor() {
		return CurrentEnv.getConfig().existsFieldTypeBindingFor(this.name, this.element);
	}

	private String getInnerClassSuperClass(boolean replaceWildcards) {
		// Arrays don't have individual binding classes
		if (this.isArray()) {
			return getConfig().bindingPathSuperClassName() + "<R, " + this.type.toString() + ">";
		}
		// Being a generic type, we have no XxxBindingPath to extend, so just extend AbstractBinding directly
		if (this.isForGenericTypeParameter()) {
			return getConfig().bindingPathSuperClassName() + "<R, " + this.getGenericElement() + ">";
		}

		// if our type is outside the binding scope and no existing binding is available,
		// we return a generic binding type
		if (!this.shouldGenerateBindingClassForType()
				&& !existsFieldTypeBindingFor() // check if type binding already exists ; if so, we can use it
				) {
			return GenericObjectBindingPath.class.getName() + "<R," + this.type.toString() + ">";
		}

		String superName = Util.lowerCaseOuterClassNames(this.element, getConfig().baseNameForBinding(this.name) + "BindingPath");
		List<String> typeArgs = Copy.list("R");
		if (this.isRawType()) {
			for (TypeParameterElement tpe : this.getElement().getTypeParameters()) {
				typeArgs.add(replaceWildcards ? "?" : tpe.toString());
			}
		} else if (this.isFixingRawType) {
			typeArgs.add(this.name.getGenericPartWithoutBrackets());
		} else if (this.type.getKind() == TypeKind.DECLARED) {
			int wildcardIndex = 0;
			for (TypeMirror tm : ((DeclaredType) this.type).getTypeArguments()) {
				if (tm.getKind() == TypeKind.WILDCARD) {
					typeArgs.add(replaceWildcards ? "?" : ("U" + wildcardIndex++));
				} else {
					typeArgs.add(tm.toString());
				}
			}
		}
		return superName + "<" + Join.commaSpace(typeArgs) + ">";
	}

	/** @return the type appropriate for setter/return arguments. */
	public String getSetType() {
		if (this.hasWildcards() && !this.isArray()) {
			List<String> dummyParams = new ArrayList<String>();
			if (this.type.getKind() == TypeKind.DECLARED) {
				DeclaredType dt = (DeclaredType) this.type;
				int wildcardIndex = 0;
				for (TypeMirror tm : dt.getTypeArguments()) {
					if (tm.getKind() == TypeKind.WILDCARD) {
						dummyParams.add("U" + (wildcardIndex++));
					} else {
						dummyParams.add(tm.toString());
					}
				}
			}
			return this.name.getWithoutGenericPart() + "<" + Join.commaSpace(dummyParams) + ">";
		}
		if (this.isRawType()) {
			List<String> dummyParams = new ArrayList<String>();
			for (TypeParameterElement tpe : this.getElement().getTypeParameters()) {
				dummyParams.add(tpe.toString());
			}
			if (dummyParams.size() > 0) {
				return this.get() + "<" + Join.commaSpace(dummyParams) + ">";
			}
		}
		return this.get();
	}

	public String getName() {
		return this.propertyName;
	}

	public TypeElement getElement() {
		return this.isTypeParameter(this.element) ? null : (TypeElement) this.element;
	}

	public TypeParameterElement getGenericElement() {
		return this.isTypeParameter(this.element) ? (TypeParameterElement) this.element : null;
	}

	public boolean isForListOrSet() {
		return "java.util.List".equals(this.name.getWithoutGenericPart()) || "java.util.Set".equals(this.name.getWithoutGenericPart());
	}

	public boolean matchesTypeParameterOfParent() {
		String type = this.name.getGenericPartWithoutBrackets();
		if (this.hasWildcards()) {
			return true;
		}
		for (TypeParameterElement e : this.enclosing.getTypeParameters()) {
			if (e.toString().equals(type)) {
				return true;
			}
		}
		return false;
	}

	/** @return "com.app.Type<String, String>" if the type is "com.app.Type<String, String>" */
	public String get() {
		return this.name.get();
	}

	public boolean doesOuterGetNeedSuppressWarnings() {
		return (this.hasWildcards() || this.isRawType()) && !this.isArray();
	}

	public boolean doesInnerClassNeedSuppressWarnings() {
		return (this.hasWildcards() || this.isRawType()) && !this.isArray();
	}

	public boolean doesInnerGetNeedSuppressWarnings() {
		return this.isFixingRawType;
	}

	public String getContainedType() {
		ClassName containedType = new ClassName(this.name.getGenericPartWithoutBrackets());
		if (containedType.get().length() > 0 && !(containedType.get().startsWith("?"))) {
			return containedType.getWithoutGenericPart() + ".class";
		} else {
			return "null";
		}
	}

	private boolean hasWildcards() {
		if (this.type.getKind() == TypeKind.DECLARED) {
			for (TypeMirror p : ((DeclaredType) this.type).getTypeArguments()) {
				if (p.getKind() == TypeKind.WILDCARD) {
					return true;
				}
			}
		}
		return false;
	}

	private boolean hasGenerics() {
		return this.type.getKind() == TypeKind.DECLARED && ((DeclaredType) this.type).getTypeArguments().size() > 0;
	}

	/** Add generic suffixes to avoid warnings in bindings for pre-1.5 APIs.
	 *
	 * This is for old pre-1.5 APIs that use, say, Enumeration. We upgrade it
	 * to something like Enumeration<String> based on the user configuration,
	 * e.g.:
	 *
	 * <code>fixRawType.javax.servlet.http.HttpServletRequest.attributeNames=String</code>
	 */
	private boolean fixRawTypeIfNeeded() {
		String fixedTypeParameter = getConfig().fixedRawType(this.enclosing, this.propertyName);
		if (!this.hasGenerics() && fixedTypeParameter != null) {
			this.name = new ClassName(this.type.toString() + "<" + fixedTypeParameter + ">");
			return true;
		}
		return false;
	}

	private boolean isForBinding() {
		return this.name.getWithoutGenericPart().endsWith("Binding");
	}

	private boolean isDeclaringClass() {
		// javac returns a stray generic (Class<E>) for inner classes where Eclipse
		// returns the right class (Class<Outer>). For now just skip it.
		return this.propertyName.equals("declaringClass");
	}

	private boolean isSkipAttributeSet() {
		return getConfig().skipAttribute(this.enclosing, this.propertyName);
	}

	private boolean isTypeParameter(Element element) {
		return element != null && element.getKind() == ElementKind.TYPE_PARAMETER;
	}

	/** @return whether the declared type has more type arguments than our usage of it does */
	private boolean isRawType() {
		if (this.isFixingRawType) {
			return false;
		}
		if (this.type.getKind() == TypeKind.DECLARED) {
			return ((DeclaredType) this.type).getTypeArguments().size() != this.getElement().getTypeParameters().size();
		}
		return false;
	}

	public TypeMirror getType() {
		return this.type;
	}

	public boolean isArray() {
		return this.isArray;
	}

	/**
	 * @return name of type that can be used as a return value
	 */
	public String getReturnableType() {
		return this.name.getWithoutGenericPart();
	}

	/** @param dummyTypes
	 * @return the toString of {@code tm} with {@code tp} replaced by {@code dummyParameter} */
	private static String toStringWithDummyParam(TypeMirror tm, TypeMirror tp, String dummyParameter, Map<TypeMirror, String> dummyTypes) {
		if (tm.getKind() == TypeKind.DECLARED) {
			DeclaredType dt = (DeclaredType) tm;
			List<String> params = new ArrayList<String>();
			for (TypeMirror ta : dt.getTypeArguments()) {
				params.add(toStringWithDummyParam(ta, tp, dummyParameter, dummyTypes));
			}
			if (params.size() == 0) {
				return tm.toString();
			} else {
				return dt.asElement().toString() + "<" + Join.commaSpace(params) + ">";
			}
		}
		if (getTypeUtils().isSameType(tm, tp)) {
			return dummyParameter;
		}
		String existingDummy = dummyTypes.get(tm);
		return existingDummy == null ? tm.toString() : existingDummy;
	}

	private static class WildcardTypeData {
		public final String dummyParam;
		public final TypeMirror extendsBound;
		public final TypeParameterElement wildcardParameter;

		public WildcardTypeData(String dummyParam) {
			this(dummyParam, null, null);
		}

		public WildcardTypeData(String dummyParam, TypeMirror extendsBound) {
			this(dummyParam, extendsBound, null);
		}

		public WildcardTypeData(String dummyParam, TypeParameterElement wildcardParameter) {
			this(dummyParam, null, wildcardParameter);
		}

		private WildcardTypeData(String dummyParam, TypeMirror extendsBound, TypeParameterElement wildcardParameter) {
			this.dummyParam = dummyParam;
			this.extendsBound = extendsBound;
			this.wildcardParameter = wildcardParameter;
		}
	}
}
