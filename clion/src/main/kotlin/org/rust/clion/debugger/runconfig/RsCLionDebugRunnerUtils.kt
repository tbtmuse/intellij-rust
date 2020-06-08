/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.clion.debugger.runconfig

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.BuildNumber
import com.jetbrains.cidr.cpp.toolchains.CPPToolchains
import com.jetbrains.cidr.cpp.toolchains.CPPToolchainsConfigurable
import com.jetbrains.cidr.toolchains.OSType
import org.rust.cargo.runconfig.CargoRunStateBase
import org.rust.debugger.runconfig.RsDebugRunnerUtils
import org.rust.debugger.runconfig.RsDebugRunnerUtils.ERROR_MESSAGE_TITLE
import org.rust.openapiext.computeWithCancelableProgress

object RsCLionDebugRunnerUtils {

    fun checkToolchainSupported(project: Project, state: CargoRunStateBase): Boolean {
        val toolSet = CPPToolchains.getInstance().defaultToolchain?.toolSet ?: return false
        if (CPPToolchains.getInstance().osType == OSType.WIN) {
            val host = project.computeWithCancelableProgress("Checking if toolchain is supported...") {
                state.rustVersion().rustc?.host.orEmpty()
            }
            val isMSVCRustToolchain = "msvc" in host
            val isGNURustToolchain = "gnu" in host

            if (ApplicationInfo.getInstance().build < BuildNumber.fromString("202")!! && isMSVCRustToolchain) {
                Messages.showErrorDialog(project, RsDebugRunnerUtils.MSVC_IS_NOT_SUPPORTED_MESSAGE, ERROR_MESSAGE_TITLE)
                return false
            }

            return when {
                isGNURustToolchain && toolSet.isMSVC -> false
                isMSVCRustToolchain && !toolSet.isMSVC -> false
                else -> true
            }
        }
        return true
    }

    fun checkToolchainConfigured(project: Project): Boolean {
        val toolchains = CPPToolchains.getInstance()
        // TODO: Fix synchronous execution on EDT
        val toolchain = toolchains.defaultToolchain
        if (toolchain == null) {
            val option = Messages.showDialog(
                project,
                "Debug toolchain is not configured.",
                ERROR_MESSAGE_TITLE,
                arrayOf("Configure"),
                Messages.OK,
                Messages.getErrorIcon()
            )
            if (option == Messages.OK) {
                ShowSettingsUtil.getInstance().showSettingsDialog(
                    project,
                    CPPToolchainsConfigurable::class.java,
                    null
                )
            }
            return false
        }
        return true
    }
}
