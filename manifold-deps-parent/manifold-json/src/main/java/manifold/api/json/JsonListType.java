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

package manifold.api.json;

import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import manifold.api.json.schema.JsonSchemaType;
import manifold.api.json.schema.JsonUnionType;
import manifold.api.json.schema.LazyRefJsonType;

/**
 */
public class JsonListType extends JsonSchemaType
{
  private IJsonType[] _componentType; // using one elem array b/c clones need to reflect this value after it is reassigned
  private Map<String, IJsonParentType> _innerTypes;

  public JsonListType( String label, URL source, JsonSchemaType parent )
  {
    super( label, source, parent );
    _innerTypes = Collections.emptyMap();
    _componentType = new IJsonType[1];
  }

  @Override
  protected void resolveRefsImpl()
  {
    super.resolveRefsImpl();
    if( _componentType[0] instanceof JsonSchemaType )
    {
      ((JsonSchemaType)_componentType[0]).resolveRefs();
    }
    else if( _componentType[0] instanceof LazyRefJsonType )
    {
      _componentType[0] = ((LazyRefJsonType)_componentType[0]).resolve();
    }
    for( Map.Entry<String, IJsonParentType> entry : new HashSet<>( _innerTypes.entrySet() ) )
    {
      IJsonType type = entry.getValue();
      if( type instanceof JsonSchemaType )
      {
        ((JsonSchemaType)type).resolveRefs();
      }
      else if( type instanceof LazyRefJsonType )
      {
        _innerTypes.put( entry.getKey(), (IJsonParentType)((LazyRefJsonType)type).resolve() );
      }
    }
  }

  @Override
  public String getLabel()
  {
    return super.getName();
  }

  public String getName()
  {
    return "ListOf" + getComponentTypeName();
  }

  private String getComponentTypeName()
  {
    if( _componentType[0] == null || _componentType[0] instanceof LazyRefJsonType )
    {
      // can happen if asked before this list type is fully configured
      return "_undefined_";
    }

    return _componentType[0] instanceof JsonUnionType ? "Object" : _componentType[0].getIdentifier();
  }

  public String getIdentifier()
  {
    return getName();
  }

  @Override
  public String getFqn()
  {
    return getName();
  }

  public void addChild( String name, IJsonParentType type )
  {
    if( _innerTypes.isEmpty() )
    {
      _innerTypes = new HashMap<>();
    }
    _innerTypes.put( name, type );
  }

  public IJsonType findChild( String name )
  {
    IJsonType innerType = _innerTypes.get( name );
    if( innerType == null )
    {
      if( _componentType[0] instanceof IJsonParentType )
      {
        innerType = ((IJsonParentType)_componentType[0]).findChild( name );
      }
      if( innerType == null )
      {
        List<IJsonType> definitions = getDefinitions();
        if( definitions != null )
        {
          for( IJsonType child : definitions )
          {
            if( child.getName().equals( name ) )
            {
              innerType = child;
              break;
            }
          }
        }
      }
    }
    return innerType;
  }

  @SuppressWarnings("WeakerAccess")
  public IJsonType getComponentType()
  {
    return _componentType[0];
  }

  public void setComponentType( IJsonType compType )
  {
    if( _componentType[0] != null && _componentType[0] != compType )
    {
      throw new IllegalStateException( "Component type already set to: " + _componentType[0].getIdentifier() + ", which is not the same as: " + compType.getIdentifier() );
    }
    _componentType[0] = compType;
  }

  @SuppressWarnings("unused")
  public Map<String, IJsonParentType> getInnerTypes()
  {
    return _innerTypes;
  }

  public JsonListType merge( IJsonType that )
  {
    if( !(that instanceof JsonListType) )
    {
      return null;
    }

    JsonListType other = (JsonListType)that;
    JsonListType mergedType = new JsonListType( getLabel(), getFile(), getParent() );

    if( !getComponentType().equalsStructurally( other.getComponentType() ) )
    {
      IJsonType componentType = Json.mergeTypes( getComponentType(), other.getComponentType() );
      if( componentType != null )
      {
        mergedType.setComponentType( componentType );
      }
      else
      {
        return null;
      }
    }

    if( !mergeInnerTypes( other, mergedType, _innerTypes ) )
    {
      return null;
    }

    return mergedType;
  }

  public void render( StringBuilder sb, int indent, boolean mutable )
  {
    for( IJsonParentType child : _innerTypes.values() )
    {
      child.render( sb, indent, mutable );
    }
  }

  @Override
  public boolean equalsStructurally( IJsonType o )
  {
    if( this == o )
    {
      return true;
    }

    if( o == null || getClass() != o.getClass() )
    {
      return false;
    }

    JsonListType that = (JsonListType)o;

    if( !_componentType[0].equalsStructurally( that._componentType[0] ) )
    {
      return false;
    }
    return _innerTypes.size() == that._innerTypes.size() &&
           _innerTypes.keySet().stream().allMatch(
             key -> that._innerTypes.containsKey( key ) &&
                    _innerTypes.get( key ).equalsStructurally( that._innerTypes.get( key ) ) );
  }

  @Override
  public boolean equals( Object o )
  {
    if( this == o )
    {
      return true;
    }

    if( isSchemaType() )
    {
      // Json Schema types must be identity compared
      return false;
    }

    if( o == null || getClass() != o.getClass() )
    {
      return false;
    }

    JsonListType that = (JsonListType)o;

    if( !_componentType[0].equals( that._componentType[0] ) )
    {
      return false;
    }
    return _innerTypes.equals( that._innerTypes );
  }

  @Override
  public int hashCode()
  {
    int result = _componentType[0].hashCode();
    result = 31 * result + _innerTypes.hashCode();
    return result;
  }
}
