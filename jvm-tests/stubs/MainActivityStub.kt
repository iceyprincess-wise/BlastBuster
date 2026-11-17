package com.splendor.blastbuster

// TEST-ONLY STUB, never shipped in the app: the JVM test compile needs
// a MainActivity symbol because CompanionService builds a PendingIntent
// for it. The real MainActivity (Agent C) references generated R, which
// does not exist outside a Gradle build, so this empty subclass stands
// in for compile purposes only. No test instantiates it.
class MainActivity : android.app.Activity()
