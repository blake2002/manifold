/*
 * Copyright (c) 2018 - Manifold Systems LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package manifold.ext;

import com.sun.tools.javac.api.BasicJavacTask;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.lang.model.type.NoType;
import javax.script.Bindings;
import manifold.ext.api.IBindingType;
import manifold.ext.api.ICallHandler;
import manifold.ext.api.ICoercionProvider;
import manifold.ext.api.IBindingsBacked;
import manifold.internal.host.RuntimeManifoldHost;
import manifold.internal.javac.ClassSymbols;
import manifold.internal.javac.IDynamicJdk;
import manifold.util.Pair;
import manifold.util.ReflectUtil;
import manifold.util.concurrent.ConcurrentHashSet;
import manifold.util.concurrent.ConcurrentWeakHashMap;

public class RuntimeMethods
{
  private static final String STRUCTURAL_PROXY = "_structuralproxy_";
  private static Map<Class, Map<Class, Constructor>> PROXY_CACHE = new ConcurrentHashMap<>();
  private static final Map<Object, Set<Class>> ID_MAP = new ConcurrentWeakHashMap<>();
  private static final Map<Class, Boolean> ICALL_HANDLER_MAP = new ConcurrentWeakHashMap<>();

  @SuppressWarnings({"UnusedDeclaration", "WeakerAccess"})
  public static Object constructProxy( Object root, Class iface )
  {
    // return findCachedProxy( root, iface ); // this is only beneficial when structural invocation happens in a loop, otherwise too costly
    return createNewProxy( root, iface );
  }

  @SuppressWarnings("UnusedDeclaration")
  public static Object assignStructuralIdentity( Object obj, Class iface )
  {
    if( obj != null )
    {
      //## note: we'd like to avoid the operation if the obj not a ICallHandler,
      // but that is an expensive structural check, more expensive than this call...
      //  if( obj is a ICallHandler )
      //  {
      Set<Class> ifaces = ID_MAP.computeIfAbsent( obj, k -> new ConcurrentHashSet<>() );
      ifaces.add( iface );
      //   }
    }
    return obj;
  }

  /**
   * Facilitates ICallHandler where the receiver of the method call structurally implements a method,
   * but the association of the structural interface with the receiver is lost.  For example:
   * <pre>
   *   Person person = Person.create(); // Person is a JsonTypeManifold interface; the runtime type of person here is really just a Map (or Binding)
   *   IMyStructureThing thing = (IMyStructureThing)person; // Extension method[s] satisfying IMyStructureThing on Person make this work e.g., via MyPersonExt extension methods class
   *   thing.foo(); // foo() is an extension method on Person e.g., defined in MyPersonExt, however the runtime type of thing is just a Map (or Binding) thus the Person type identity is lost
   * </pre>
   */
  //## todo: this is inefficient, we should consider caching the methods by signature along with the interfaces
  public static Object invokeUnhandled( Object thiz, Class proxiedIface, String name, Class returnType, Class[] paramTypes, Object[] args )
  {
    Set<Class> ifaces = ID_MAP.get( thiz );
    if( ifaces != null )
    {
      for( Class iface : ifaces )
      {
        if( iface == proxiedIface )
        {
          continue;
        }

        Method m = findMethod( iface, name, paramTypes );
        if( m != null )
        {
          try
          {
            Object result = m.invoke( constructProxy( thiz, iface ), args );
            result = coerce( result, returnType );
            return result;
          }
          catch( Exception e )
          {
            throw new RuntimeException( e );
          }
        }
      }
    }
    return ICallHandler.UNHANDLED;
  }

  @SuppressWarnings("WeakerAccess")
  public static Object coerce( Object value, Class<?> type )
  {
    if( value == null )
    {
      if( type.isPrimitive() )
      {
        return defaultPrimitiveValue( type );
      }
      return null;
    }

    if( type.isPrimitive() )
    {
      type = box( type );
    }

    Class<?> valueClass = value.getClass();
    if( valueClass == type || type.isAssignableFrom( valueClass ) )
    {
      return value;
    }

    Object result = callCoercionProviders( value, type );
    if( result != ICallHandler.UNHANDLED )
    {
      return result;
    }

    Object boxedValue = coerceBoxed( value, type );
    if( boxedValue != null )
    {
      return boxedValue;
    }

    if( type == BigInteger.class )
    {
      if( value instanceof Number )
      {
        return BigInteger.valueOf( ((Number)value).longValue() );
      }
      if( value instanceof Boolean )
      {
        return ((Boolean)value) ? BigInteger.ONE: BigInteger.ZERO;
      }
      return new BigInteger( value.toString() );
    }

    if( type == BigDecimal.class )
    {
      if( value instanceof Boolean )
      {
        return ((Boolean)value) ? BigDecimal.ONE: BigDecimal.ZERO;
      }
      return new BigDecimal( value.toString() );
    }

    if( type == String.class )
    {
      return String.valueOf( value );
    }

    if( type.isEnum() )
    {
      String name = String.valueOf( value );
      //noinspection unchecked
      return Enum.valueOf( (Class<Enum>)type, name );
    }

    if( type.isArray() && valueClass.isArray() )
    {
      int length = Array.getLength( value );
      Class<?> componentType = type.getComponentType();
      Object array = Array.newInstance( componentType, length );
      for( int i = 0; i < length; i++ )
      {
        Array.set( array, i, coerce( Array.get( value, i ), componentType ) );
      }
      return array;
    }

    // let the ClassCastException happen
    return value;
  }

  private static Object defaultPrimitiveValue( Class<?> type )
  {
    if( type == int.class ||
        type == short.class )
    {
      return 0;
    }
    if( type == byte.class )
    {
      return (byte)0;
    }
    if( type == long.class )
    {
      return 0L;
    }
    if( type == float.class )
    {
      return 0f;
    }
    if( type == double.class )
    {
      return 0d;
    }
    if( type == boolean.class )
    {
      return false;
    }
    if( type == char.class )
    {
      return (char)0;
    }
    if( type == void.class )
    {
      return null;
    }
    throw new IllegalArgumentException( "Unsupported primitive type: " + type.getSimpleName() );
  }

  private static Object coerceBoxed( Object value, Class<?> type )
  {
    if( type == Boolean.class || type == boolean.class )
    {
      if( value instanceof Number )
      {
        return ((Number)value).intValue() != 0;
      }
      return Boolean.parseBoolean( value.toString() );
    }

    if( type == Byte.class || type == byte.class )
    {
      if( value instanceof Number )
      {
        return ((Number)value).byteValue() != 0;
      }
      if( value instanceof Boolean )
      {
        return ((Boolean)value) ? (byte)1: (byte)0;
      }
      return Byte.parseByte( value.toString() );
    }

    if( type == Character.class || type == char.class )
    {
      if( value instanceof Number )
      {
        return (char)((Number)value).intValue();
      }
      String s = value.toString();
      return s.isEmpty() ? (char)0 : s.charAt( 0 );
    }

    if( type == Short.class || type == short.class )
    {
      if( value instanceof Number )
      {
        return ((Number)value).shortValue();
      }
      if( value instanceof Boolean )
      {
        return ((Boolean)value) ? (short)1: (short)0;
      }
      return Short.parseShort( value.toString() );
    }

    if( type == Integer.class || type == int.class )
    {
      if( value instanceof Number )
      {
        return ((Number)value).intValue();
      }
      if( value instanceof Boolean )
      {
        return ((Boolean)value) ? 1: 0;
      }
      return Integer.parseInt( value.toString() );
    }

    if( type == Long.class || type == long.class )
    {
      if( value instanceof Number )
      {
        return ((Number)value).longValue();
      }
      if( value instanceof Boolean )
      {
        return ((Boolean)value) ? 1L: 0L;
      }
      return Long.parseLong( value.toString() );
    }

    if( type == Float.class || type == float.class )
    {
      if( value instanceof Number )
      {
        return ((Number)value).floatValue();
      }
      if( value instanceof Boolean )
      {
        return ((Boolean)value) ? 1f: 0f;
      }
      return Float.parseFloat( value.toString() );
    }

    if( type == Double.class || type == double.class )
    {
      if( value instanceof Number )
      {
        return ((Number)value).doubleValue();
      }
      if( value instanceof Boolean )
      {
        return ((Boolean)value) ? 1d : 0d;
      }
      return Double.parseDouble( value.toString() );
    }
    return null;
  }

  private static Object callCoercionProviders( Object value, Class<?> type )
  {
    for( ICoercionProvider coercer: CoercionProviders.get() )
    {
      Object coercedValue = coercer.coerce( value, type );
      if( coercedValue != ICallHandler.UNHANDLED )
      {
        return coercedValue;
      }
    }
    return ICallHandler.UNHANDLED;
  }

  private static Class<?> box( Class<?> type )
  {
    if( type == boolean.class )
    {
      return Boolean.class;
    }
    if( type == byte.class )
    {
      return Byte.class;
    }
    if( type == char.class )
    {
      return Character.class;
    }
    if( type == short.class )
    {
      return Short.class;
    }
    if( type == int.class )
    {
      return Integer.class;
    }
    if( type == long.class )
    {
      return Long.class;
    }
    if( type == float.class )
    {
      return Float.class;
    }
    if( type == double.class )
    {
      return Double.class;
    }
    throw new IllegalStateException();
  }

  private static Method findMethod( Class<?> iface, String name, Class[] paramTypes )
  {
    try
    {
      Method m = iface.getDeclaredMethod( name, paramTypes );
      if( m == null )
      {
        for( Class superIface : iface.getInterfaces() )
        {
          m = findMethod( superIface, name, paramTypes );
          if( m != null )
          {
            break;
          }
        }
      }
      if( m != null )
      {
        return m;
      }
    }
    catch( Exception e )
    {
      return null;
    }
    return null;
  }

  private static Object createNewProxy( Object root, Class<?> iface )
  {
    if( root == null )
    {
      return null;
    }

    Class rootClass = root.getClass();
    if( iface.isAssignableFrom( rootClass ) )
    {
      return root;
    }

    if( root instanceof Bindings && IBindingsBacked.class.isAssignableFrom( iface ) )
    {
      // An interface extending IBindingsBacked is expected to define a proxy(Bindings) method returning an
      // implementation of the interface, see JsonStructureType.
      // This strategy avoids costs otherwise involved with generating and compiling the proxy at runtime via ICallHandler.
      return ReflectUtil.method( iface, "proxy", Bindings.class ).invokeStatic( root );
    }
    
//    final Field classRedefinedCount;
//    try
//    {
//      classRedefinedCount = Class.class.getDeclaredField( "classRedefinedCount" );
//      classRedefinedCount.setAccessible( true );
//      System.out.println( "### " + iface.getSimpleName() + ": " + classRedefinedCount.getInt( iface ) );
//    }
//    catch( Exception e )
//    {
//      throw new RuntimeException( e );
//    }

    Map<Class, Constructor> proxyByClass = PROXY_CACHE.get( iface );
    if( proxyByClass == null )
    {
      PROXY_CACHE.put( iface, proxyByClass = new ConcurrentHashMap<>() );
    }
    Constructor proxyClassCtor = proxyByClass.get( rootClass );
    if( proxyClassCtor == null ) //|| BytecodeOptions.JDWP_ENABLED.get() )
    {
      Class proxyClass = createProxy( iface, rootClass );
      proxyByClass.put( rootClass, proxyClassCtor = proxyClass.getConstructors()[0] );
    }
    try
    {
      // in Java 9 in modular mode the proxy class belongs to the owner's module,
      // therefore we need to make it accessible from the manifold module before
      // calling newInstance()
      ReflectUtil.setAccessible( proxyClassCtor );
      return proxyClassCtor.newInstance( root );
    }
    catch( Exception e )
    {
      throw new RuntimeException( e );
    }
  }

  private static Class createProxy( Class iface, Class rootClass )
  {
    String relativeProxyName = rootClass.getCanonicalName().replace( '.', '_' ) + STRUCTURAL_PROXY + iface.getCanonicalName().replace( '.', '_' );
    if( hasCallHandlerMethod( rootClass ) )
    {
      return DynamicTypeProxyGenerator.makeProxy( iface, rootClass, relativeProxyName );
    }
    return StructuralTypeProxyGenerator.makeProxy( iface, rootClass, relativeProxyName );
  }

  private static boolean hasCallHandlerMethod( Class rootClass )
  {
    if( ICallHandler.class.isAssignableFrom( rootClass ) )
    {
      // Nominally implements ICallHandler
      return true;
    }
    if( ReflectUtil.method( rootClass, "call", Class.class, String.class, String.class, Class.class, Class[].class, Object[].class ) != null )
    {
      // Structurally implements ICallHandler
      return true;
    }

    // maybe has an extension satisfying ICallHandler
    return hasCallHandlerFromExtension( rootClass );
  }

  private static boolean hasCallHandlerFromExtension( Class rootClass )
  {
    Boolean isCallHandler = ICALL_HANDLER_MAP.get( rootClass );
    if( isCallHandler != null )
    {
      return isCallHandler;
    }

    String fqn = rootClass.getCanonicalName();
    BasicJavacTask javacTask = RuntimeManifoldHost.get().getJavaParser().getJavacTask();
    Pair<Symbol.ClassSymbol, JCTree.JCCompilationUnit> classSymbol = ClassSymbols.instance( RuntimeManifoldHost.get().getSingleModule() ).getClassSymbol( javacTask, fqn );
    Pair<Symbol.ClassSymbol, JCTree.JCCompilationUnit> callHandlerSymbol = ClassSymbols.instance( RuntimeManifoldHost.get().getSingleModule() ).getClassSymbol( javacTask, ICallHandler.class.getCanonicalName() );
    if( Types.instance( javacTask.getContext() ).isAssignable( classSymbol.getFirst().asType(), callHandlerSymbol.getFirst().asType() ) )
    {
      // Nominally implements ICallHandler
      isCallHandler = true;
    }
    else
    {
      // Structurally implements ICallHandler
      isCallHandler = hasCallMethod( javacTask, classSymbol.getFirst() );
    }
    ICALL_HANDLER_MAP.put( rootClass, isCallHandler );
    return isCallHandler;
  }

  private static boolean hasCallMethod( BasicJavacTask javacTask, Symbol.ClassSymbol classSymbol )
  {
    Name call = Names.instance( javacTask.getContext() ).fromString( "call" );
    Iterable<Symbol> elems = IDynamicJdk.instance().getMembersByName( classSymbol, call );
    for( Symbol s : elems )
    {
      if( s instanceof Symbol.MethodSymbol )
      {
        List<Symbol.VarSymbol> parameters = ((Symbol.MethodSymbol)s).getParameters();
        if( parameters.size() != 6 )
        {
          return false;
        }

        Symtab symbols = Symtab.instance( javacTask.getContext() );
        Types types = Types.instance( javacTask.getContext() );
        return types.erasure( parameters.get( 0 ).asType() ).equals( types.erasure( symbols.classType ) ) &&
               parameters.get( 1 ).asType().equals( symbols.stringType ) &&
               parameters.get( 2 ).asType().equals( symbols.stringType ) &&
               types.erasure( parameters.get( 3 ).asType() ).equals( types.erasure( symbols.classType ) ) &&
               parameters.get( 4 ).asType() instanceof Type.ArrayType && types.erasure( ((Type.ArrayType)parameters.get( 4 ).asType()).getComponentType() ).equals( types.erasure( symbols.classType ) ) &&
               parameters.get( 5 ).asType() instanceof Type.ArrayType && ((Type.ArrayType)parameters.get( 5 ).asType()).getComponentType().equals( symbols.objectType );
      }
    }
    Type superclass = classSymbol.getSuperclass();
    if( !(superclass instanceof NoType) )
    {
      if( hasCallMethod( javacTask, (Symbol.ClassSymbol)superclass.tsym ) )
      {
        return true;
      }
    }
    for( Type iface : classSymbol.getInterfaces() )
    {
      if( hasCallMethod( javacTask, (Symbol.ClassSymbol)iface.tsym ) )
      {
        return true;
      }
    }
    return false;
  }

  public static Object coerceToBindingValue( Map thiz, Object arg )
  {
    if( arg instanceof IBindingType )
    {
      arg = ((IBindingType)arg).toBindingValue();
    }
    else if( needsCoercion( arg, thiz ) )
    {
      for( ICoercionProvider coercer: CoercionProviders.get() )
      {
        Object coercedValue = coercer.toBindingValue( arg );
        if( coercedValue != ICallHandler.UNHANDLED )
        {
          arg = coercedValue;
        }
      }
    }
    return arg;
  }
  private static boolean needsCoercion( Object arg, Map thiz )
  {
    return thiz instanceof Bindings &&
           !(arg instanceof Bindings) &&
           !isPrimitiveType( arg.getClass() );
  }
  private static boolean isPrimitiveType( Class<?> type )
  {
    return type == String.class ||
           type == Boolean.class ||
           type == Character.class ||
           type == Byte.class ||
           type == Short.class ||
           type == Integer.class ||
           type == Long.class ||
           type == Float.class ||
           type == Double.class;
  }
}
