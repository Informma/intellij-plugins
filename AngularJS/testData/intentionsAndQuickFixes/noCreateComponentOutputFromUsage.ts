// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import {Component} from '@angular/core';

@Component({
             standalone: true,
             selector: 'app-root',
             template: `<div [title]="emi<caret>tter.emit($event)"></div>`
           })
export class AppComponent {
}