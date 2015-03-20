package org.codehaus.mojo.latex;

/*
 * Copyright 2010 INRIA / CITI Laboratory / Amazones Research Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

import static java.lang.String.format;
import static org.apache.commons.exec.CommandLine.parse;
import static org.apache.commons.io.FileUtils.copyDirectory;
import static org.apache.commons.io.FileUtils.copyFile;
import static org.apache.commons.io.FileUtils.iterateFiles;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.io.FileUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;

/**
 * LaTeX documents building goal.
 *
 * @author Julien Ponge
 * @goal latex
 * @phase compile
 */
public final class LaTeXMojo extends AbstractMojo
{

    /**
     * The documents root.
     *
     * @parameter expression="${latex.docsRoot}" default-value="src/main/latex"
     * @required
     */
    private File docsRoot;

    /**
     * Common files directory inside the documents root (the only directory to be skipped).
     *
     * @parameter expression="${latex.commonsDirName}" default-value="common"
     * @required
     */
    private String commonsDirName;

    /**
     * The Maven build directory.
     *
     * @parameter expression="${project.build.directory}"
     * @required
     * @readonly
     */
    private File buildDir;

    /**
     * The LaTeX builds directory.
     *
     * @parameter expression="${project.latex.build.directory}" default-value="${project.build.directory}/latex"
     * @required
     */
    private File latexBuildDir;

    /**
     * Path to the LaTeX binaries installation.
     *
     * @parameter expression="${latex.binariesPath}" default-value=""
     */
    private String binariesPath;

    /**
     * Indicates whether to run 'makeglossaries' or not.
     * 
     * @parameter expression="${latex.makeGlossaries}" default-value="false"
     */
    private boolean makeGlossaries;

    public void execute()
        throws MojoExecutionException, MojoFailureException
    {
        try
        {
            final File[] docDirs = getDocDirs();
            final File[] buildDirs = prepareLaTeXBuildDirectories( docDirs );
            buildDocuments( buildDirs );
        }
        catch ( final IOException e )
        {
            getLog().error( e );
            throw new MojoFailureException( e.getMessage() );
        }
    }

    private File[] prepareLaTeXBuildDirectories( final File[] docDirs ) throws IOException
    {
        final File[] buildDirs = new File[docDirs.length];
        final File commonsDir = new File( docsRoot, commonsDirName );
    
        for ( int i = 0; i < docDirs.length; i++ )
        {
            final File dir = docDirs[i];
            final File target = new File( latexBuildDir, docDirs[i].getName() );
            buildDirs[i] = target;
    
            copyDirectory( dir, target );
            if ( commonsDir.exists() )
            {
                copyDirectory( commonsDir, target );
            }
    
            @SuppressWarnings( "unchecked" )
            final Iterator<File> iterator = iterateFiles( target, new String[]{ ".svn" }, true );
            while ( iterator.hasNext() )
            {
                FileUtils.deleteDirectory( (File) iterator.next() );
            }
    
        }
    
        return buildDirs;
    }

    private File[] getDocDirs()
    {
        return docsRoot.listFiles( createCommonsDirNameFilter() );
    }

    private FileFilter createCommonsDirNameFilter()
    {
        return new FileFilter()
        {
            public boolean accept( final File pathname )
            {
                return pathname.isDirectory() 
                        && !( pathname.getName().equals( commonsDirName ) )
                        && !( pathname.isHidden() );
            }
        };
    }

    private void buildDocuments( final File[] buildDirs ) throws IOException, MojoFailureException
    {
        for ( final File dir : buildDirs )
        {
            final File texFile = new File( dir, dir.getName() + ".tex" );
            final File pdfFile = new File( dir, dir.getName() + ".pdf" );

            if ( requiresBuilding(dir, pdfFile) )
            {
                final CommandLine pdfLaTeX = createPdfLaTeXCommandLine( texFile );
                executeBibtexIfNecessary( pdfLaTeX, dir );
                executeMakeGlossariesIfNecessary( dir );
                execute( pdfLaTeX, dir );
                execute( pdfLaTeX, dir );
                copyPdfFileToBuildDir( pdfFile, dir );
            }
            else
            {
                if ( getLog().isInfoEnabled() )
                {
                    getLog().info( format( "Skipping: no LaTeX changes detected in %s",  dir.getCanonicalPath() ) );
                }
            }
        }
    }

    private boolean requiresBuilding( final File dir, final File pdfFile )
    {
        @SuppressWarnings( "unchecked" )
        final Collection<File> texFiles = FileUtils.listFiles( dir, new String[]{ ".tex", ".bib" }, true );

        if ( pdfFileDoesNotExist( pdfFile ) )
        {
            return true;
        }
        for ( final File texFile : texFiles )
        {
            if ( FileUtils.isFileNewer( texFile, pdfFile ) )
            {
                return true;
            }
        }
        return false;
    }

    private static boolean pdfFileDoesNotExist( final File pdfFile )
    {
        return !pdfFile.exists();
    }

    private CommandLine createPdfLaTeXCommandLine( final File texFile )
    {
        return  createCommandLine( "pdflatex", "-shell-escape", "--halt-on-error", texFile.getAbsolutePath() );
    }

    private CommandLine createCommandLine( final String commandName, final String ... arguments )
    {
        CommandLine result = parse( executablePath( commandName ) );
        for ( final String argument : arguments )
        {
            result = result.addArgument( argument );
        }
        logDebugMessageIfEnabled( format( "%s: %s", commandName, result ) );
        return result;
    }

    private String executablePath( final String executable )
    {
        if ( binariesPath == null )
        {
            return executable;
        }
        return new StringBuilder().append( binariesPath ).append( File.separator ).append( executable ).toString();
    }

    private void logDebugMessageIfEnabled( final String debugMessage ) {
        if ( getLog().isDebugEnabled() )
        {
            getLog().debug( debugMessage );
        }
    }

    private void executeBibtexIfNecessary( final CommandLine pdfLaTeX, final File dir )
            throws MojoFailureException, IOException
    {
        final File bibFile = new File( dir, format( "%s.bib", dir.getName() ) );
        if ( bibFile.exists() ) {
            execute( pdfLaTeX, dir );
            final CommandLine bibTeX = createCommandLine( "bibtex", dir.getName() );
            execute( bibTeX, dir );
        }
    }

    private void execute( final CommandLine commandLine, final File dir ) throws IOException, MojoFailureException
    {
        final DefaultExecutor executor = new DefaultExecutor();
        executor.setWorkingDirectory( dir );
        if ( executor.execute( commandLine ) != 0 )
        {
            throw new MojoFailureException( "Error code returned for: " + commandLine.toString() );
        }
    }

    private void executeMakeGlossariesIfNecessary( final File dir )
            throws MojoFailureException, IOException
    {
        if ( makeGlossaries )
        {
            final CommandLine makeglossaries = createCommandLine( "makeglossaries", dir.getName() );
            execute( makeglossaries, dir );
        }
    }

    private void copyPdfFileToBuildDir( final File pdfFile, final File dir ) throws IOException {
        final File destFile = new File( buildDir, pdfFile.getName() );
        copyFile( pdfFile, destFile );
    }

}
