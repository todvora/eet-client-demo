## eet-client demo application

This repository represents a demo implementation of the [todvora/eet-client](https://github.com/todvora/eet-client).
 
Please continue to Application.java to see all sources.

## What this demo does
This demo will send two requests to the EET playground endpoint, both under `CommunicationMode.REAL` settings.
The app uses `CZ683555118` client key, provided by EET to test playground communication.
 
First request is going as first submission and it will be printed if everything is OK or saved to a simulated `database` for later resubmission.
  
Then we will try to resubmit all currrent queued requests that failed before. There is one 
  hardcoded in the demo app. If the first request failed, it will be repeated too.

## Run

How to start the application:

### From command line
Open your command line and switch to the directory of the project (pom.xml has to be there).

Type following commands:

```
mvn clean
mvn exec:java
```

This will compile the application and start Application.main method.

### From your IDE
Open / import the project as `Maven Project`. Locate Application.java file and let your
 ide run it.