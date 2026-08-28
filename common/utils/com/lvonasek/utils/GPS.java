package com.lvonasek.utils;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;

public class GPS implements LocationListener {

  private int count;

  private Location lastLocation;

  private LocationManager locationManager;

  public void start(Context context, int stopAfter) {

    count = stopAfter;
    locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);

    // Location access can be revoked at any time. Do not call the provider when
    // neither accepted location permission is currently granted.
    if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        != PackageManager.PERMISSION_GRANTED
        && context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        != PackageManager.PERMISSION_GRANTED) {
      return;
    }

    try {
      locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 250, 20, this);
    } catch (Exception e) {
      e.printStackTrace();
    }

    try {
      locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 250, 50, this);
    } catch (Exception e) {
      e.printStackTrace();
    }

    Location gps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
    if (gps != null) {
      lastLocation = gps;
    }
    Location net = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
    if (net != null) {
      lastLocation = net;
    }
  }

  public Location getLastLocation() {
    return lastLocation;
  }

  public void stop() {
    try {
      locationManager.removeUpdates(this);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  @Override
  public void onLocationChanged(Location location) {
    lastLocation = location;

    if (count > 0) {
      count--;
      if (count == 0)
        stop();
    }
  }

  @Override
  public void onStatusChanged(String provider, int status, Bundle extras) {

  }

  @Override
  public void onProviderEnabled(String provider) {

  }

  @Override
  public void onProviderDisabled(String provider) {

  }
}
