package org.jenkinsci.plugins.maveninvoker;

/*
 * Copyright (c) Olivier Lamy
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;

import org.apache.maven.plugin.invoker.model.BuildJob;
import org.apache.maven.plugin.invoker.model.io.xpp3.BuildJobXpp3Reader;
import org.jenkinsci.plugins.maveninvoker.results.MavenInvokerResult;
import org.jenkinsci.plugins.maveninvoker.results.MavenInvokerResults;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * @author Olivier Lamy
 */
public class MavenInvokerRecorder
    extends Recorder
{

    @Extension
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

    public String filenamePattern = "target/invoker-reports/BUILD*.xml";

    @DataBoundConstructor
    public MavenInvokerRecorder( String filenamePattern )
    {
        this.filenamePattern = filenamePattern;
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService()
    {
        return BuildStepMonitor.STEP;
    }

    @Override
    public boolean perform( AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener )
        throws InterruptedException, IOException
    {
        PrintStream logger = listener.getLogger();
        logger.println( "performing MavenInvokerRecorder, filenamePattern:'" + filenamePattern + "'" );
        FilePath[] filePaths = locateReports( build.getWorkspace(), filenamePattern );
        logger.println( "Found reports:" + Arrays.asList( filePaths ) );
        try
        {
            MavenInvokerResults mavenInvokerResults = parseReports( filePaths, listener, build );
            MavenInvokerBuildAction action = new MavenInvokerBuildAction( build, mavenInvokerResults );
            build.addAction( action );
        }
        catch ( Exception e )
        {
            throw new IOException( e.getMessage(), e );
        }
        return true;
    }

    static MavenInvokerResults parseReports( FilePath[] filePaths, BuildListener listener, AbstractBuild<?, ?> build )
        throws Exception
    {
        final PrintStream logger = listener.getLogger();
        MavenInvokerResults mavenInvokerResults = new MavenInvokerResults();
        final BuildJobXpp3Reader reader = new BuildJobXpp3Reader();
        saveReports( getMavenInvokerReportsDirectory( build ), filePaths );
        for ( final FilePath filePath : filePaths )
        {
            BuildJob buildJob = reader.read( filePath.read() );
            MavenInvokerResult mavenInvokerResult = map( buildJob );
            mavenInvokerResults.mavenInvokerResults.add( mavenInvokerResult );
        }
        logger.println( "Finished parsing Maven Invoker results" );
        return mavenInvokerResults;
    }

    static MavenInvokerResult map( BuildJob buildJob )
    {
        MavenInvokerResult mavenInvokerResult = new MavenInvokerResult();

        // mavenInvokerResult.buildLog
        mavenInvokerResult.description = buildJob.getDescription();
        mavenInvokerResult.failureMessage = buildJob.getFailureMessage();
        mavenInvokerResult.name = buildJob.getName();
        mavenInvokerResult.project = buildJob.getProject();
        mavenInvokerResult.result = buildJob.getResult();
        mavenInvokerResult.time = buildJob.getTime();
        return mavenInvokerResult;
    }

    /**
     * save reports
     */
    static boolean saveReports( FilePath maveninvokerDir, FilePath[] paths )
    {
        try
        {
            maveninvokerDir.mkdirs();
            int i = 0;
            for ( FilePath report : paths )
            {
                String name = "maven-invoker-result" + ( i > 0 ? "-" + i : "" ) + ".xml";
                i++;
                FilePath dst = maveninvokerDir.child( name );
                report.copyTo( dst );
            }
        }
        catch ( Exception e )
        {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    static boolean saveBuildLogs( FilePath backupDirectory, List<FilePath> paths )
    {
        try
        {
            backupDirectory.mkdirs();
            for ( FilePath buildLog : paths )
            {
                File file = new File( buildLog.getRemote() );
                String name = file.getParentFile().getName() + "-build.log";
                FilePath dst = backupDirectory.child( name );
                buildLog.copyTo( dst );
            }
        }
        catch ( Exception e )
        {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    /**
     * Gets the directory to store report files
     */
    static FilePath getMavenInvokerReportsDirectory( AbstractBuild<?, ?> build )
    {
        return new FilePath( new File( build.getRootDir(), "maven-invoker-plugin-reports" ) );
    }

    static FilePath[] locateBuildLogs( FilePath workspace, String basePath )
        throws IOException, InterruptedException
    {
        return workspace.list( basePath + "/build.log" );
    }

    static FilePath[] locateReports( FilePath workspace, String filenamePattern )
        throws IOException, InterruptedException
    {
        // First use ant-style pattern
        try
        {
            FilePath[] ret = workspace.list( filenamePattern );
            if ( ret.length > 0 )
            {
                return ret;
            }
        }
        catch ( Exception e )
        {
        }

        // If it fails, do a legacy search
        List<FilePath> files = new ArrayList<FilePath>();
        String parts[] = filenamePattern.split( "\\s*[;:,]+\\s*" );
        for ( String path : parts )
        {
            FilePath src = workspace.child( path );
            if ( src.exists() )
            {
                if ( src.isDirectory() )
                {
                    files.addAll( Arrays.asList( src.list( "**/BUILD*.xml" ) ) );
                }
                else
                {
                    files.add( src );
                }
            }
        }
        return files.toArray( new FilePath[files.size()] );
    }

    public static final class DescriptorImpl
        extends BuildStepDescriptor<Publisher>
    {
        @Override
        public boolean isApplicable( Class<? extends AbstractProject> aClass )
        {
            return true;
        }

        @Override
        public String getDisplayName()
        {
            // FIXME i18n
            return "Maven Invoker Plugin Report";
        }
    }
}
