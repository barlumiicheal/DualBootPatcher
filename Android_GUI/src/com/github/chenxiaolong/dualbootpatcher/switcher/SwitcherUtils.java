/*
 * Copyright (C) 2014-2015  Andrew Gunnerson <andrewgunnerson@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.github.chenxiaolong.dualbootpatcher.switcher;

import android.content.Context;
import android.support.annotation.Nullable;
import android.util.Log;

import com.github.chenxiaolong.dualbootpatcher.RomUtils;
import com.github.chenxiaolong.dualbootpatcher.ThreadUtils;
import com.github.chenxiaolong.dualbootpatcher.Version;
import com.github.chenxiaolong.dualbootpatcher.Version.VersionParseException;
import com.github.chenxiaolong.dualbootpatcher.nativelib.LibMbp.BootImage;
import com.github.chenxiaolong.dualbootpatcher.nativelib.LibMbp.CpioFile;
import com.github.chenxiaolong.dualbootpatcher.nativelib.LibMbp.Device;
import com.github.chenxiaolong.dualbootpatcher.nativelib.LibMbp.PatcherConfig;
import com.github.chenxiaolong.dualbootpatcher.nativelib.LibMbp.PatcherError;
import com.github.chenxiaolong.dualbootpatcher.patcher.PatcherUtils;
import com.github.chenxiaolong.dualbootpatcher.socket.MbtoolSocket;
import com.github.chenxiaolong.dualbootpatcher.socket.MbtoolUtils;
import com.github.chenxiaolong.dualbootpatcher.socket.MbtoolUtils.Feature;

import org.apache.commons.io.Charsets;
import org.apache.commons.io.IOUtils;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Enumeration;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class SwitcherUtils {
    public static final String TAG = SwitcherUtils.class.getSimpleName();

    private static final String ZIP_MULTIBOOT_DIR = "multiboot/";
    private static final String ZIP_INFO_PROP = ZIP_MULTIBOOT_DIR + "info.prop";
    private static final String PROP_INSTALLER_VERSION = "mbtool.installer.version";
    private static final String PROP_INSTALL_LOCATION = "mbtool.installer.install-location";

    public static String getBootPartition(Context context) {
        String realCodename = RomUtils.getDeviceCodename(context);
        String bootBlockDev = null;

        PatcherConfig pc = new PatcherConfig();
        for (Device d : pc.getDevices()) {
            boolean matches = false;

            for (String codename : d.getCodenames()) {
                if (realCodename.equals(codename)) {
                    matches = true;
                    break;
                }
            }

            if (matches) {
                String[] bootBlockDevs = d.getBootBlockDevs();
                if (bootBlockDevs.length > 0) {
                    bootBlockDev = bootBlockDevs[0];
                }
                break;
            }
        }
        pc.destroy();

        return bootBlockDev;
    }

    public static String[] getBlockDevSearchDirs(Context context) {
        String realCodename = RomUtils.getDeviceCodename(context);
        PatcherConfig pc = new PatcherConfig();
        for (Device d : pc.getDevices()) {
            boolean matches = false;

            for (String codename : d.getCodenames()) {
                if (realCodename.equals(codename)) {
                    matches = true;
                    break;
                }
            }

            if (matches) {
                String[] dirs = d.getBlockDevBaseDirs();
                pc.destroy();
                return dirs;
            }
        }
        pc.destroy();

        return null;
    }

    public static VerificationResult verifyZipMbtoolVersion(String zipFile) {
        ThreadUtils.enforceExecutionOnNonMainThread();

        ZipFile zf = null;

        try {
            zf = new ZipFile(zipFile);

            boolean isMultiboot = false;

            Properties prop = null;

            final Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                final ZipEntry ze = entries.nextElement();

                if (ze.getName().startsWith(ZIP_MULTIBOOT_DIR)) {
                    isMultiboot = true;
                }
                if (ze.getName().equals(ZIP_INFO_PROP)) {
                    prop = new Properties();
                    prop.load(zf.getInputStream(ze));
                    break;
                }
            }

            if (!isMultiboot) {
                return VerificationResult.ERROR_NOT_MULTIBOOT;
            }

            if (prop == null) {
                return VerificationResult.ERROR_VERSION_TOO_OLD;
            }

            Version version = new Version(prop.getProperty(PROP_INSTALLER_VERSION, "0.0.0"));
            Version minVersion = MbtoolUtils.getMinimumRequiredVersion(Feature.IN_APP_INSTALLATION);

            if (version.compareTo(minVersion) < 0) {
                return VerificationResult.ERROR_VERSION_TOO_OLD;
            }

            return VerificationResult.NO_ERROR;
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            return VerificationResult.ERROR_ZIP_NOT_FOUND;
        } catch (IOException e) {
            e.printStackTrace();
            return VerificationResult.ERROR_ZIP_READ_FAIL;
        } catch (VersionParseException e) {
            e.printStackTrace();
            return VerificationResult.ERROR_VERSION_TOO_OLD;
        } finally {
            IOUtils.closeQuietly(zf);
        }
    }

    public static String getTargetInstallLocation(String zipFile) {
        ZipFile zf = null;

        try {
            zf = new ZipFile(zipFile);

            Properties prop = null;

            final Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                final ZipEntry ze = entries.nextElement();

                if (ze.getName().equals(ZIP_INFO_PROP)) {
                    prop = new Properties();
                    prop.load(zf.getInputStream(ze));
                    break;
                }
            }

            if (prop == null) {
                return null;
            }

            return prop.getProperty(PROP_INSTALL_LOCATION, null);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            return null;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        } finally {
            IOUtils.closeQuietly(zf);
        }
    }

    /**
     * Get ROM ID from a boot image file
     *
     * This will first attempt to read /romid from the ramdisk (new-style). If /romid does not
     * exist, the "romid=..." parameter in the kernel command line will be read (old-style). If
     * neither are present, the boot image is assumed to be associated with an unpatched primary ROM
     * and thus "primary" will be returned.
     *
     * @param context Context
     * @param filename Absolute path to boot image file
     * @return String containing the ROM ID or null if an error occurs within libmbp.
     */
    @Nullable
    public static String getBootImageRomId(Context context, String filename) {
        BootImage bi = new BootImage();
        CpioFile cf = new CpioFile();

        try {
            if (!bi.load(filename)) {
                PatcherError error = bi.getError();
                Log.e(TAG, "libmbp error: " + PatcherUtils.getErrorMessage(context, error));
                error.destroy();
                return null;
            }

            if (!cf.load(bi.getRamdiskImage())) {
                PatcherError error = cf.getError();
                Log.e(TAG, "libmbp error: " + PatcherUtils.getErrorMessage(context, error));
                error.destroy();
                return null;
            }

            // Try reading /romid from the ramdisk
            String romId;
            byte[] data = cf.getContents("romid");
            if (data != null) {
                romId = new String(data, Charsets.UTF_8);
                int newline = romId.indexOf('\n');
                if (newline >= 0) {
                    romId = romId.substring(0, newline);
                }
                return romId;
            }

            // Try reading "romid=SOMETHING" from the kernel cmdline
            String[] kernelArgs = bi.getKernelCmdline().split("\\s+");
            for (String arg : kernelArgs) {
                if (arg.startsWith("romid=")) {
                    return arg.substring(6);
                }
            }

            return "primary";
        } finally {
            bi.destroy();
            cf.destroy();
        }
    }

    public enum VerificationResult {
        NO_ERROR,
        ERROR_ZIP_NOT_FOUND,
        ERROR_ZIP_READ_FAIL,
        ERROR_NOT_MULTIBOOT,
        ERROR_VERSION_TOO_OLD
    }

    public static void reboot(final Context context) {
        new Thread() {
            @Override
            public void run() {
                try {
                    MbtoolSocket.getInstance().restart(context, "");
                } catch (IOException e) {
                    // Ignore
                    Log.e(TAG, "mbtool communication error", e);
                }
            }
        }.start();
    }
}
