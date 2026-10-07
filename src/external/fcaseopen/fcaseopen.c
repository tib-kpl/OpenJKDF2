#include "fcaseopen.h"

#if !defined(_WIN32)
#include <stdlib.h>
#include <string.h>

#include <dirent.h>
#include <errno.h>
#include <unistd.h>

#if 0
static int is_directory(const char *path) {
   struct stat statbuf;
   if (stat(path, &statbuf) != 0)
       return 0;
   return S_ISDIR(statbuf.st_mode);
}
#endif

#if defined(__ANDROID__)
#define FCASEOPEN_DIRCACHE
#endif

#ifdef FCASEOPEN_DIRCACHE
#include <limits.h>
#include <pthread.h>

// Added: directory listing cache. Every resource the engine opens first misses
// as a loose file, and each miss walked the directories with readdir(). On
// Android's FUSE shared storage (a game folder in /storage/emulated/0) a
// directory read costs ~0.5ms, which made level loads take several times
// longer. Listings (and missing directories) are kept until something is
// written, created or deleted, or the working directory changes.
typedef struct casepath_dir
{
    char *path;
    int exists;
    int numNames;
    char **names;
    struct casepath_dir *next;
} casepath_dir;

static casepath_dir *casepath_dirs = NULL;
static char casepath_cwd[PATH_MAX];
static pthread_mutex_t casepath_mutex = PTHREAD_MUTEX_INITIALIZER;

static void casepath_flush_locked(void)
{
    while (casepath_dirs)
    {
        casepath_dir *next = casepath_dirs->next;
        for (int i = 0; i < casepath_dirs->numNames; i++)
            free(casepath_dirs->names[i]);
        free(casepath_dirs->names);
        free(casepath_dirs->path);
        free(casepath_dirs);
        casepath_dirs = next;
    }
}

static casepath_dir *casepath_get_dir_locked(const char *path)
{
    for (casepath_dir *it = casepath_dirs; it; it = it->next)
    {
        if (!strcmp(it->path, path))
            return it;
    }

    casepath_dir *dir = calloc(1, sizeof(casepath_dir));
    if (!dir)
        return NULL;
    dir->path = strdup(path);
    if (!dir->path)
    {
        free(dir);
        return NULL;
    }

    DIR *d = opendir(path);
    if (d)
    {
        int capacity = 0;
        struct dirent *e;
        dir->exists = 1;
        while ((e = readdir(d)) != NULL)
        {
            if (dir->numNames == capacity)
            {
                int newCapacity = capacity ? capacity * 2 : 32;
                char **newNames = realloc(dir->names, newCapacity * sizeof(char *));
                if (!newNames)
                    break;
                dir->names = newNames;
                capacity = newCapacity;
            }
            dir->names[dir->numNames] = strdup(e->d_name);
            if (dir->names[dir->numNames])
                dir->numNames++;
        }
        closedir(d);
    }

    dir->next = casepath_dirs;
    casepath_dirs = dir;
    return dir;
}

void casepath_invalidate(void)
{
    pthread_mutex_lock(&casepath_mutex);
    casepath_flush_locked();
    pthread_mutex_unlock(&casepath_mutex);
}

static int casepath_cached_locked(char const *path, char *r)
{
    size_t l = strlen(path);
    char *p = alloca(l + 16);
    strcpy(p, path);
    size_t rl = 0;

    if (p[0] == '/')
    {
        r[0] = 0;
        p = p + 1;
    }
    else
    {
        // Relative paths are cached against the current directory
        char cwd[PATH_MAX];
        if (!getcwd(cwd, sizeof(cwd)))
            cwd[0] = 0;
        if (strcmp(cwd, casepath_cwd))
        {
            casepath_flush_locked();
            strncpy(casepath_cwd, cwd, sizeof(casepath_cwd) - 1);
            casepath_cwd[sizeof(casepath_cwd) - 1] = 0;
        }

        r[0] = '.';
        r[1] = 0;
        rl = 1;
    }

    int last = 0;
    char *c = strsep(&p, "/");
    while (c)
    {
        if (last)
            return 0;

        casepath_dir *dir = casepath_get_dir_locked(rl ? r : "/");
        if (!dir || !dir->exists)
            return 0;

        r[rl] = '/';
        rl += 1;
        r[rl] = 0;

        const char *match = NULL;
        if (!strcmp(c, "."))
        {
            // Not listed on every filesystem (Android FUSE)
            match = c;
        }
        else
        {
            for (int i = 0; i < dir->numNames; i++)
            {
                if (strcasecmp(c, dir->names[i]) == 0)
                {
                    match = dir->names[i];
                    break;
                }
            }
        }

        if (match)
        {
            strcpy(r + rl, match);
            rl += strlen(match);
        }
        else
        {
            strcpy(r + rl, c);
            rl += strlen(c);
            last = 1;
        }

        c = strsep(&p, "/");
    }

    return 1;
}
#else
void casepath_invalidate(void)
{
}
#endif // FCASEOPEN_DIRCACHE

// r must have strlen(path) + 2 bytes
int casepath(char const *path, char *r)
{
#ifdef FCASEOPEN_DIRCACHE
    pthread_mutex_lock(&casepath_mutex);
    int ret = casepath_cached_locked(path, r);
    pthread_mutex_unlock(&casepath_mutex);
    return ret;
#else
    size_t l = strlen(path);
    char *p = alloca(l + 16);
    strcpy(p, path);
    size_t rl = 0;
    
    DIR *d;
    if (p[0] == '/')
    {
        d = opendir("/");
        p = p + 1;
    }
    else
    {
        d = opendir(".");
        r[0] = '.';
        r[1] = 0;
        rl = 1;
    }
    
    int last = 0;
    char *c = strsep(&p, "/");
    while (c)
    {
        if (!d)
        {
            return 0;
        }
        
        if (last)
        {
            closedir(d);
            return 0;
        }
        
        r[rl] = '/';
        rl += 1;
        r[rl] = 0;
        
        struct dirent *e = readdir(d);
        while (e)
        {
            if (strcasecmp(c, e->d_name) == 0)
            {
                strcpy(r + rl, e->d_name);
                rl += strlen(e->d_name);

                closedir(d);
                d = opendir(r);
                
                break;
            }
            
            e = readdir(d);
        }
        
        if (!e)
        {
            strcpy(r + rl, c);
            rl += strlen(c);
            last = 1;
        }

        c = strsep(&p, "/");
    }

#if 0
    // Added
    if(is_directory(r)) {
        strcat(r, "/");
    }
#endif
    
    if (d) closedir(d);
    return 1;
#endif // FCASEOPEN_DIRCACHE
}
#else
void casepath_invalidate(void)
{
}
#endif

FILE *fcaseopen(char const *path, char const *mode)
{
    FILE *f = fopen(path, mode);
#if !defined(_WIN32)
    if (!f)
    {
        char *r = malloc(strlen(path) + 16);
        if (casepath(path, r))
        {
            f = fopen(r, mode);
        }
        if (r)
            free(r);
    }

    // Added: writing may have created a file the cached listings don't have
    if (strpbrk(mode, "wa+"))
        casepath_invalidate();
#endif
    return f;
}

void casechdir(char const *path)
{
#if !defined(_WIN32)
    char *r = malloc(strlen(path) + 16);
    if (casepath(path, r))
    {
        chdir(r);
    }
    else
    {
        errno = ENOENT;
    }
    if (r)
        free(r);
    casepath_invalidate();
#else
    chdir(path);
#endif
}
