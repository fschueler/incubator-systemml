/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */


package org.apache.sysml.runtime.matrix.data;

import org.apache.hadoop.io.IntWritable;

public class TaggedInt extends Tagged<IntWritable>
{
	
	public TaggedInt()
	{
		tag=-1;
		base=new IntWritable();
	}

	public TaggedInt(IntWritable b, byte t) {
		super(b, t);
	}
	
	public int hashCode()
	{
		return base.hashCode()+tag;
	}
	
	public int compareTo(TaggedInt other)
	{
		if(this.tag!=other.tag)
			return (this.tag-other.tag);
		else if(this.base.get()!=other.base.get())
			return (this.base.get()-other.base.get());
		return 0;
	}

	@Override
	public boolean equals(Object other)
	{
		if( !(other instanceof TaggedInt))
			return false;
		
		TaggedInt tother = (TaggedInt)other;
		return (this.tag==tother.tag && this.base.get()==tother.base.get());
	}
}
