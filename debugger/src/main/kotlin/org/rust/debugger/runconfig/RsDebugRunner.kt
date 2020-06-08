/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.debugger.runconfig

import com.intellij.openapi.project.Project
import org.rust.cargo.runconfig.CargoRunStateBase

class RsDebugRunner : RsDebugRunnerBase() {

    override fun checkToolchainSupported(project: Project, state: CargoRunStateBase): Boolean =
        RsDebugRunnerUtils.checkToolchainSupported(project, state)

    override fun checkToolchainConfigured(project: Project): Boolean =
        RsDebugRunnerUtils.checkToolchainConfigured(project)
}
