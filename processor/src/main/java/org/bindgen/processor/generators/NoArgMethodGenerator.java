package org.bindgen.processor.generators;

import javax.lang.model.element.ExecutableElement;

import joist.sourcegen.GClass;

/**
 * Generates bindings for no-arg methods that return something (non void) 
 * @author mihai
 *
 */
public class NoArgMethodGenerator extends GetterMethodGenerator {

	public NoArgMethodGenerator(GClass outerClass, ExecutableElement method) throws WrongGeneratorException {
		super(outerClass, method);
	}

	@Override
	protected void checkViability() throws WrongGeneratorException {
		if (AccessorPrefix.NONE != this.prefix
			|| this.hasSetterMethod()
			|| this.methodReturnsVoid()
			|| this.methodHasParameters()
			|| this.methodThrowsExceptions()) {
			throw new WrongGeneratorException();
		}
	}

	public static class Factory extends ExecutableElementGeneratorFactory {
		@Override
		public NoArgMethodGenerator newGenerator(GClass outerClass, ExecutableElement method) throws WrongGeneratorException {
			return new NoArgMethodGenerator(outerClass, method);
		}
	}

}
