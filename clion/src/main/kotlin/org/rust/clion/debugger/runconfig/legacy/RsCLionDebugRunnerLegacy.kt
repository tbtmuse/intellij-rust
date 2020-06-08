/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.clion.debugger.runconfig.legacy

import com.intellij.openapi.project.Project
import org.rust.cargo.runconfig.CargoRunStateBase
import org.rust.clion.debugger.runconfig.RsCLionDebugRunnerUtils
import org.rust.debugger.runconfig.legacy.RsDebugRunnerLegacyBase

class RsCLionDebugRunnerLegacy : RsDebugRunnerLegacyBase() {
    override fun checkToolchainSupported(project: Project, state: CargoRunStateBase): Boolean =
        RsCLionDebugRunnerUtils.checkToolchainSupported(project, state)

    override fun checkToolchainConfigured(project: Project): Boolean =
        RsCLionDebugRunnerUtils.checkToolchainConfigured(project)
}
