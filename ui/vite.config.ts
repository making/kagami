import {defineConfig} from 'vite'
import react from '@vitejs/plugin-react'

// https://vitejs.dev/config/
export default defineConfig({
    plugins: [react()],
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
        },
    },
})
