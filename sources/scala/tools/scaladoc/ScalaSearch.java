/*     ____ ____  ____ ____  ______                                     *\
**    / __// __ \/ __// __ \/ ____/    SOcos COmpiles Scala             **
**  __\_ \/ /_/ / /__/ /_/ /\_ \       (c) 2002, LAMP/EPFL              **
** /_____/\____/\___/\____/____/                                        **
\*                                                                      */

// $Id$

package scala.tools.scaladoc;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

import ch.epfl.lamp.util.Pair;

import scalac.Global;
import scalac.symtab.Scope;
import scalac.symtab.Scope.SymbolIterator;
import scalac.symtab.Symbol;
import scalac.symtab.Type;
import scalac.symtab.Modifiers;
import scalac.util.Debug;
import scalac.util.Name;
import scalac.util.NameTransformer;

import java.io.*;
import scalac.ast.printer.*;
import ch.epfl.lamp.util.SourceFile;
import scalac.ast.parser.Parser;
import scalac.Unit;

/**
 * This class contains functions to retrieve information from a Scala
 * library.
 */
public class ScalaSearch {

    /////////////////// SYMBOLS TESTERS ////////////////////////////

    /** Test if the given symbol is a class, a package, or an object.
     */
    static boolean isContainer(Symbol sym) {
	return sym.isClass() || sym.isModule() || sym.isPackage();
    }

    /** Test if the given symbol has a lazy type.
     */
    static boolean isLazy(Symbol sym) {
	return
	    (sym.rawInfo() instanceof Type.LazyType)  ||
	    ((sym.rawInfo() instanceof Type.TypeRef)  &&
	     (sym.rawInfo().symbol().rawInfo() instanceof Type.LazyType));
    }

    /** Test if the given symbol is private (without evaluating its type).
     */
    static boolean isPrivate(Symbol sym) {
	return (sym.flags & Modifiers.PRIVATE) != 0;
    }

    /** Test if the given symbol has been generated by the compiler.
     */
    public static boolean isGenerated(Symbol sym) {
	return
	    (sym.isSynthetic() && !sym.isRoot()) ||
	    (sym.isGenerated() &&
	     NameTransformer.decode(sym.name).toString().equals(sym.name.toString())) ||
	    NameTransformer.decode(sym.name).toString().endsWith("_=");
    }

    /** Test if the given symbol is an empty java module generated to
     * contain static fields and methods of a java class.
     */
    public static boolean isEmptyJavaModule(Symbol sym) {
        return sym.isModule() && sym.isJava() && !sym.isPackage() && (members(sym).length == 0);
    }

    /** Test if the given symbol is access method for a val.
     */
    public static boolean isValMethod(Symbol sym) {
        return  (sym.isInitializedMethod() && (sym.flags & Modifiers.STABLE) != 0);
    }

    /** Test if the given symbol is relevant for the documentation.
     */
    public static boolean isRelevant(Symbol sym) {
	return !isGenerated(sym) && !isLazy(sym) && !isPrivate(sym) &&
	    !(sym.isPackage() && sym.isClass()) && !sym.isConstructor() &&
            !sym.isCaseFactory() && !isEmptyJavaModule(sym);
    }

    //////////////////////// SCOPE ITERATOR //////////////////////////////

    /** A symbol iterator that returns all alternatives of an overloaded symbol
     *  instead of the overloaded symbol itself (does not unload lazy symbols).
     */
    public static class UnloadLazyIterator extends SymbolIterator {
        private SymbolIterator iterator;
        private Symbol[] alternatives;
        private int index;

        public UnloadLazyIterator(SymbolIterator iterator) {
            this.iterator = iterator;
            this.alternatives = null;
            this.index = -1;
        }

        public boolean hasNext() {
            return index >=  0 || iterator.hasNext();
        }
        public Symbol next() {
            if (index >= 0) {
                Symbol symbol = alternatives[index++];
                if (index == alternatives.length) {
                    alternatives = null;
                    index = -1;
                }
                return symbol;
            } else {
                Symbol symbol = iterator.next();
		if (isLazy(symbol))
		    return symbol;
		else {
		    switch (symbol.type()) {
		    case OverloadedType(Symbol[] alts, _):
			alternatives = alts;
			index = 0;
			return next();
		    default:
			return symbol;
		    }
		}
            }
        }
    }

    ////////////////////////// TRAVERSER ///////////////////////////////

    /** Function from Symbol to void.
     */
    public static abstract class SymFun {
	abstract void apply(Symbol sym);
    }

    /** Apply a given function to all symbols below the given symbol
     * in the symbol table.
     */
    public static void foreach(Symbol sym, SymFun fun) {
	if (isRelevant(sym)) {
	    fun.apply(sym);
	    Symbol[] members = members(sym);
	    for(int i = 0; i < members.length; i++)
		foreach(members[i], fun);
	}
    }

    /** Return all members of a container symbol.
     */
    public static Symbol[] members(Symbol sym) {
	if (isContainer(sym) && !isLazy(sym)) {
	    List memberList = new LinkedList();
	    SymbolIterator i =
                new UnloadLazyIterator(sym.members().iterator(false));
	    while (i.hasNext()) {
		Symbol member = i.next();
		if (isRelevant(member))
		    memberList.add(member);
	    }
	    return (Symbol[]) memberList.toArray(new Symbol[memberList.size()]);
	}
	else
	    return new Symbol[0];
    }

    /** Apply a given function to all symbols below the given symbol
     * in the symbol table.
     */
    public static void foreach(Symbol sym, SymFun fun,
                               SymbolBooleanFunction isDocumented) {
	if (isDocumented.apply(sym) && isRelevant(sym)) {
	    fun.apply(sym);
	    Symbol[] members = members(sym, isDocumented);
	    for(int i = 0; i < members.length; i++)
		foreach(members[i], fun, isDocumented);
	}
    }

    /** Return all members of a container symbol.
     */
    public static Symbol[] members(Symbol sym,
                                   SymbolBooleanFunction isDocumented) {
	if (isContainer(sym) && !isLazy(sym)) {
	    List memberList = new LinkedList();
	    SymbolIterator i =
                new UnloadLazyIterator(sym.members().iterator(false));
	    while (i.hasNext()) {
		Symbol member = i.next();
		if (isDocumented.apply(member) && isRelevant(sym))
		    memberList.add(member);
	    }
	    return (Symbol[]) memberList.toArray(new Symbol[memberList.size()]);
	}
	else
	    return new Symbol[0];
    }

    ///////////////////////// COMPARATORS ///////////////////////////////

    /**
     * Use the simple name of symbols to order them.
     */
    public static Comparator symAlphaOrder = new Comparator() {
	    public int compare(Object o1, Object o2) {
		Symbol symbol1 = (Symbol) o1;
		Symbol symbol2 = (Symbol) o2;
		String name1 = symbol1.nameString();
 		String name2 = symbol2.nameString();
		return name1.compareTo(name2);
	    }
	    public boolean equals(Object o) {
		return false;
	    }
	};

    /**
     * Use the fully qualified name of symbols to order them.
     */
    public static Comparator symPathOrder = new Comparator() {
	    public int compare(Object o1, Object o2) {
		Symbol symbol1 = (Symbol) o1;
		Symbol symbol2 = (Symbol) o2;
		String name1 = symbol1.fullName().toString();
 		String name2 = symbol2.fullName().toString();
		return name1.compareTo(name2);
	    }
	    public boolean equals(Object o) {
		return false;
	    }
	};

    ///////////////////////// COLLECTORS ////////////////////////////

    /**
     * Returns the sorted list of packages from the root symbol.
     *
     * @param root
     */
    public static Symbol[] getSortedPackageList(Symbol root, SymbolBooleanFunction isDocumented) {
	final List packagesAcc = new LinkedList();
	foreach(root,
		new SymFun() {
		    void apply(Symbol sym) {
			if (sym.isPackage())
			    packagesAcc.add(sym);
		    }
		}, isDocumented);
	Symbol[] packages = (Symbol[]) packagesAcc.toArray(new Symbol[packagesAcc.size()]);
	Arrays.sort(packages, symPathOrder);
	return packages;
    }

    public static Symbol[][] getSubContainerMembers(Symbol root, SymbolBooleanFunction isDocumented) {
	final List objectsAcc = new LinkedList();
	final List traitsAcc = new LinkedList();
	final List classesAcc = new LinkedList();
	foreach(root,
		new SymFun() {
		    void apply(Symbol sym) {
			if (sym.isTrait() && !sym.isModuleClass())
			    traitsAcc.add(sym);
			else if (sym.isClass() && !sym.isModuleClass())
			    classesAcc.add(sym);
			else if (sym.isModule() && !sym.isPackage())
			    objectsAcc.add(sym);
		    }
		}, isDocumented
		);
	Symbol[] objects = (Symbol[]) objectsAcc.toArray(new Symbol[objectsAcc.size()]);
	Symbol[] traits  = (Symbol[]) traitsAcc.toArray(new Symbol[traitsAcc.size()]);
	Symbol[] classes = (Symbol[]) classesAcc.toArray(new Symbol[classesAcc.size()]);
        Arrays.sort(objects, symAlphaOrder);
	Arrays.sort(traits, symAlphaOrder);
	Arrays.sort(classes, symAlphaOrder);
	return new Symbol[][]{ objects, traits, classes };
    }

    public static Symbol[][] splitMembers(Symbol[] syms) {
	List fields = new LinkedList();
	List methods = new LinkedList();
        List objects = new LinkedList();
        List traits = new LinkedList();
        List classes = new LinkedList();
	List packages = new LinkedList();
        for (int i = 0; i < syms.length; i++) {
	    Symbol sym = syms[i];
	    if (sym.isTrait()) traits.add(sym);
	    else if (sym.isClass()) classes.add(sym);
	    else if (sym.isPackage()) packages.add(sym);
	    else if (sym.isModule()) objects.add(sym);
	    else if (sym.isMethod() && !isValMethod(sym)) methods.add(sym);
	    else fields.add(sym);
        }
        return new Symbol[][] {
	    (Symbol[]) fields.toArray(new Symbol[fields.size()]),
	    (Symbol[]) methods.toArray(new Symbol[methods.size()]),
	    (Symbol[]) objects.toArray(new Symbol[objects.size()]),
	    (Symbol[]) traits.toArray(new Symbol[traits.size()]),
	    (Symbol[]) classes.toArray(new Symbol[classes.size()]),
	    (Symbol[]) packages.toArray(new Symbol[packages.size()])
	};
    }

    /////////////////// IMPLEMENTING CLASSES OR OBJECTS //////////////////////

    /**
     * Returns a hashtable which maps each class symbol to the list of
     * its direct sub-classes or sub-modules. We also keep track of
     * the exact involved type.
     * Result type = Map<Symbol, List<Pair<Symbol, Type>>
     *
     * @param root
     */
    public static Map subTemplates(Symbol root, SymbolBooleanFunction isDocumented) {
	final Map subs = new HashMap();
	foreach(root, new SymFun() { void apply(Symbol sym) {
	    if (sym.isClass() || sym.isModule()) {
		Type[] parents = sym.moduleClass().parents();
		for (int i = 0; i < parents.length; i++) {
		    Symbol parentSymbol = parents[i].symbol();
		    List subList = (List) subs.get(parentSymbol);
		    if (subList == null) {
			subList = new LinkedList();
			subs.put(parentSymbol, subList);
		    }
		    subList.add(new Pair(sym, parents[i]));
		}
	    }
	}
	}, isDocumented);
	return subs;
    }

    //////////////////////// INDEX BUILDER /////////////////////////////

    /**
     * Returns the list of characters with the sorted list of
     * members starting with a character.
     * Result type = Pair<Character[], Map<Character, Symbol[]>>
     *
     * @param root
     */
    public static Pair index(Symbol root, final SymbolBooleanFunction isDocumented) {
	final Map index = new HashMap();
	// collecting
	foreach(root, new SymFun() { void apply(Symbol sym) {
	    String name = sym.nameString();
	    if (name.length() > 0) {
		char ch = Character.toUpperCase(name.charAt(0));
		Character unicode = new Character(ch);
		List symList = (List) index.get(unicode);
		if (symList == null) {
		    symList = new LinkedList();
		    index.put(unicode, symList);
		}
		symList.add(sym);
	    }
	}
	    }, isDocumented);
	// sorting
	Character[] chars = (Character[]) index.keySet()
	    .toArray(new Character[index.keySet().size()]);
	Arrays.sort(chars);
	for (int i = 0; i < chars.length; i++) {
	    Character car = chars[i];
	    List symList = (List) index.get(car);
	    Symbol[] syms = (Symbol[]) symList.toArray(new Symbol[symList.size()]);
	    Arrays.sort(syms, symAlphaOrder);
	    index.put(car, syms);
	}
	return new Pair(chars, index);
    }

    //////////////////////////// INHERITED MEMBERS //////////////////////////////

    /**
     * Finds all local and inherited members of a given class or
     * object.
     *
     * @param sym
     */
    public static Symbol[] collectMembers(Symbol sym) {
	Type thistype = sym.thisType();
	Name[] names = collectNames(thistype);
	List/*<Symbol>*/ members = new LinkedList();
	for (int i = 0; i < names.length; i++) {
	    Symbol member = thistype.lookup(names[i]);
	    if (member != Symbol.NONE)
                members.add(member);
	}
	List unloadedMembers = new LinkedList();
	Iterator it = members.iterator();
	while (it.hasNext()) {
	    Symbol[] alts = ((Symbol) it.next()).alternativeSymbols();
	    for (int i = 0; i < alts.length; i++)
                if (isRelevant(alts[i]))
                    unloadedMembers.add(alts[i]);
	}
	return (Symbol[]) unloadedMembers.toArray(new Symbol[unloadedMembers.size()]);
    }

    // where
    protected static Name[] collectNames(Type tpe) {
	List names = new LinkedList();
	collectNames(tpe, names);
	return (Name[]) names.toArray(new Name[names.size()]);
    }

    // where
    protected static void collectNames(Type tpe, List/*<Name>*/ names) {
	// local members
        SymbolIterator it =
            new UnloadLazyIterator(tpe.members().iterator(false));
	while (it.hasNext()) {
	    Name name = ((Symbol) it.next()).name;
	    if (!names.contains(name))
		names.add(name);
	}
	// inherited members
	Type[] parents = tpe.parents();
	for (int i = 0; i < parents.length; i++)
	    collectNames(parents[i], names);
    }

    /**
     * Groups symbols with respect to their owner and sort the owners
     * by name.
     *
     * @param syms
     */
    public static Pair/*<Symbol[], Map<Symbol, Symbol[]>>*/ groupSymbols(Symbol[] syms) {
	Map/*<Symbol, List>*/ groups = new HashMap();
	for (int i = 0; i < syms.length; i++) {
	    List group = (List) groups.get(syms[i].owner());
	    if (group == null) {
		group = new LinkedList();
		groups.put(syms[i].owner(), group);
	    }
	    group.add(syms[i]);
	}
	Symbol[] owners =
            (Symbol[]) groups.keySet().toArray(new Symbol[groups.keySet().size()]);
	Arrays.sort(owners, symPathOrder);
	for (int i = 0; i < owners.length; i++) {
	    List groupList = (List) groups.get(owners[i]);
	    Symbol[] group =
                (Symbol[]) groupList.toArray(new Symbol[groupList.size()]);
	    Arrays.sort(group, symAlphaOrder);
	    groups.put(owners[i], group);
	}
	return new Pair(owners, groups);
    }

    //////////////////////////// OVERRIDEN SYMBOL //////////////////////////////

    public static Symbol overridenBySymbol(Symbol sym) {
        Type base = Type.compoundTypeWithOwner(sym.owner(),
                                      sym.owner().info().parents(),
                                      Scope.EMPTY);
        return sym.overriddenSymbol(base);
    }

    ////////////////////////// POST TYPECHECKING ////////////////////////

    public static int queryCounter = 0;

    /**
     * Parse a string representing a Scala type and resolve its
     * symbols. This function must be called after the typechecking
     * phase. If parsing or type checking fails, return Type.NoType.
     */
    public static Type typeOfString(String typeString, Global global) {
        int errorNumber = global.reporter.errors();
        String unitString = "trait tmp$" + queryCounter +
            " extends Option[unit] { def f" + typeString + "; }";
        // Rem: we use a dummy extends clause, otherwise the compiler
        // complains.
        queryCounter = queryCounter + 1;
        InputStream in =
            new BufferedInputStream(new ByteArrayInputStream(unitString.getBytes()));
        //            new BufferedInputStream(new StringBufferInputStream(unitString));
        SourceFile sourceFile = null;
        try {
            sourceFile = new SourceFile("tmp.scala", in);
        } catch(IOException e) { }
        Unit tmpUnit = new Unit(global, sourceFile, false);
        tmpUnit.body = new Parser(tmpUnit).parse();
        //TreePrinter treePrinter = new TextTreePrinter(System.out);
        //treePrinter.print(tmpUnit);
        global.PHASE.ANALYZER.phase().apply(new Unit[]{ tmpUnit });
        if (global.reporter.errors() == errorNumber) {
            Scope tmpScope = tmpUnit.body[0].symbol().members();
            Type res = tmpScope.lookup(Name.fromString("f")).type();
            return res;
        }
        else
            return Type.NoType;
    }

}

//////////////////////////// DOCUMENTED SYMBOLS //////////////////////////////

/** Compute documented symbols. */
public class DocSyms {

    Set syms;

    DocSyms(Global global, Symbol[] packages) {
	syms = new HashSet();
	for(int i = 0; i < packages.length; i++) {
	    Symbol pack = packages[i];
	    // add all sub-members.
	    ScalaSearch.foreach(pack,
				new ScalaSearch.SymFun() {
				    public void apply(Symbol sym) {
					syms.add(sym);
				    }
				}
				);
	    // add all super packages.
	    Symbol owner = pack.owner();
	    while (owner != Symbol.NONE) {
		syms.add(owner.module());
		owner = owner.owner();
	    }
	}
    }

    public boolean contains(Symbol sym) {
	boolean res = false;
	if (sym.isParameter())
	    res = contains(sym.classOwner());
	else
	    res = (syms.contains(sym) || syms.contains(sym.module()));
	return res;
    }
}

public abstract class SymbolBooleanFunction {
    public abstract boolean apply(Symbol sym);
}
