import { Component, ErrorInfo, ReactNode } from 'react'
import { AlertCircle, RefreshCw } from 'lucide-react'
import './ErrorBoundary.css'

interface Props {
  children: ReactNode
}

interface State {
  hasError: boolean
  error: Error | null
}

class ErrorBoundary extends Component<Props, State> {
  constructor(props: Props) {
    super(props)
    this.state = { hasError: false, error: null }
  }

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('[ErrorBoundary] Uncaught error:', error, info.componentStack)
  }

  handleReset = () => {
    this.setState({ hasError: false, error: null })
  }

  render() {
    if (this.state.hasError) {
      return (
        <div className="error-boundary-overlay">
          <AlertCircle size={48} className="error-boundary-icon" />
          <h2 className="error-boundary-title">
            Something went wrong
          </h2>
          <p className="error-boundary-message">
            The Camera Driver UI encountered an unexpected error.
          </p>
          {this.state.error && (
            <pre className="error-boundary-pre">
              {this.state.error.message}
            </pre>
          )}
          <button className="error-boundary-btn" onClick={this.handleReset}>
            <RefreshCw size={14} />
            Retry
          </button>
        </div>
      )
    }

    return this.props.children
  }
}

export default ErrorBoundary
