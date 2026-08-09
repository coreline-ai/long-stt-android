package com.stt.benchmark.recording;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.FeatureInfo;
import android.content.pm.ServiceInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class RecorderServiceContractInstrumentedTest {
    @Test
    public void manifestDeclaresOptionalMicrophoneAndTypedForegroundService() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PackageManager packageManager = context.getPackageManager();
        PackageInfo packageInfo = packageManager.getPackageInfo(
                context.getPackageName(),
                PackageManager.GET_PERMISSIONS | PackageManager.GET_CONFIGURATIONS
        );
        assertTrue(Arrays.asList(packageInfo.requestedPermissions).contains(Manifest.permission.RECORD_AUDIO));
        assertTrue(Arrays.asList(packageInfo.requestedPermissions).contains(Manifest.permission.FOREGROUND_SERVICE_MICROPHONE));
        FeatureInfo microphone = Arrays.stream(packageInfo.reqFeatures)
                .filter(feature -> PackageManager.FEATURE_MICROPHONE.equals(feature.name))
                .findFirst()
                .orElseThrow(AssertionError::new);
        assertTrue((microphone.flags & FeatureInfo.FLAG_REQUIRED) == 0);

        ServiceInfo service = packageManager.getServiceInfo(
                new ComponentName(context, RecorderService.class),
                PackageManager.GET_META_DATA
        );
        assertFalse(service.exported);
        assertTrue((service.getForegroundServiceType() & ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) != 0);
        assertTrue((service.flags & ServiceInfo.FLAG_STOP_WITH_TASK) == 0);
    }
}
