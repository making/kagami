import {defineConfig} from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// https://vitejs.dev/config/
export default defineConfig({
    plugins: [react(), tailwindcss()],
    server: {
        proxy: {
            '/repositories': {
                target: 'http://localhost:8080',
                changeOrigin: true,
            },
            '/artifacts': {
                target: 'http://localhost:8080',
                changeOrigin: true,
            },
            '/login': {
                target: 'http://localhost:8080',
                changeOrigin: true,
            },
            '/logout': {
                target: 'http://localhost:8080',
                changeOrigin: true,
            },
            '/me': {
                target: 'http://localhost:8080',
                changeOrigin: true,
            },
            '/fonts': {
                target: 'http://localhost:8080',
                changeOrigin: true,
            },
            '/favicon.svg': {
                target: 'http://localhost:8080',
                changeOrigin: true,
            },
        },
    },
})
