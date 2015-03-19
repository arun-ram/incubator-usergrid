/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.usergrid.corepersistence.io.read;


import java.util.ArrayList;
import java.util.List;

import org.apache.usergrid.corepersistence.io.reduce.StreamReducer;
import org.apache.usergrid.persistence.model.entity.Id;

import rx.Observable;
import rx.functions.Func1;


/**
 * The builder to hold the list of traversal commands
 */
public class CommandBuilder {

    private final Id root;
    private final List<Command<Id>> commandList;


    public CommandBuilder( final Id root ) {this.root = root;
        commandList = new ArrayList<>(  );
    }

    public void addIntermediateCommand(final Command<Id> command){
      commandList.add( command );
    }


    public <T> void addFinalCommand( final Command<T> command, final StreamReducer<T> reducer ) {

        Observable.just("foo").flatMap( new Func1<String, Observable<?>>() {
            @Override
            public Observable<?> call( final String s ) {
                return null;
            }
        };
    }


    public List<Command<Id>> getCommands(){
        return  commandList;
    }
}
